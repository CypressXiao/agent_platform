package com.agentplatform.gateway.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 企业内部 Embedding 模型适配器
 * 通过 HTTP 调用企业内部的 Embedding 服务，兼容 Spring AI EmbeddingModel 接口
 *
 * 支持的 format：
 *   - dashscope : 阿里云 DashScope 多模态格式（qwen3-vl-embedding）
 *   - openai    : OpenAI 兼容格式（text-embedding-v4 等）
 */
@Slf4j
public class EnterpriseEmbeddingModel implements EmbeddingModel {

    /** 支持的 API 格式 */
    public enum Format { DASHSCOPE, OPENAI }

    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final int dimension;
    private final Format format;
    private final int maxRetryAttempts;
    private final int retryDelayMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EnterpriseEmbeddingModel(String endpoint, String apiKey,
                                     String modelName, int dimension,
                                     int timeoutSeconds, String formatStr) {
        this(endpoint, apiKey, modelName, dimension, timeoutSeconds, formatStr, 3, 500);
    }

    public EnterpriseEmbeddingModel(String endpoint, String apiKey,
                                     String modelName, int dimension,
                                     int timeoutSeconds, String formatStr,
                                     int maxRetryAttempts, int retryDelayMs) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimension = dimension;
        this.format = parseFormat(formatStr);
        this.maxRetryAttempts = maxRetryAttempts;
        this.retryDelayMs = retryDelayMs;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        log.info("EnterpriseEmbeddingModel initialized: model={}, format={}, retry={}", 
            modelName, this.format, maxRetryAttempts);
    }

    private Format parseFormat(String formatStr) {
        if (formatStr == null) return Format.OPENAI;
        return switch (formatStr.toLowerCase()) {
            case "dashscope" -> Format.DASHSCOPE;
            default -> Format.OPENAI;
        };
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<float[]> embeddings = embedBatch(texts);

        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            results.add(new Embedding(embeddings.get(i), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embedBatch(texts);
    }

    private List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embedSingle).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private float[] embedSingle(String text) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                String bodyJson = objectMapper.writeValueAsString(buildRequestBody(text));

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Embedding API error: " + response.statusCode() + " - " + response.body());
                }

                return parseResponse(objectMapper.readValue(response.body(), Map.class));

            } catch (Exception e) {
                lastException = e;
                log.warn("Embedding attempt {}/{} failed [format={}]: {}", 
                    attempt, maxRetryAttempts, format, e.getMessage());
                if (attempt < maxRetryAttempts) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);  // 递增延迟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        log.error("Enterprise embedding failed after {} attempts", maxRetryAttempts, lastException);
        throw new RuntimeException("Enterprise embedding failed after " + maxRetryAttempts + " attempts", lastException);
    }

    /**
     * 按 format 构建请求体
     */
    private Map<String, Object> buildRequestBody(String text) {
        return switch (format) {
            case DASHSCOPE ->
                // qwen3-vl-embedding 多模态格式
                Map.of(
                    "model", modelName,
                    "input", Map.of("contents", List.of(Map.of("text", text))),
                    "parameters", Map.of("dimension", dimension, "output_type", "dense")
                );
            case OPENAI ->
                // OpenAI 兼容格式（text-embedding-v4 等）
                Map.of(
                    "model", modelName,
                    "input", text
                );
        };
    }

    /**
     * 按 format 解析响应
     */
    @SuppressWarnings("unchecked")
    private float[] parseResponse(Map<String, Object> result) {
        List<Double> vec = switch (format) {
            case DASHSCOPE -> {
                // {"output": {"embeddings": [{"embedding": [...]}]}}
                Map<String, Object> output = (Map<String, Object>) result.get("output");
                List<Map<String, Object>> embeddings = (List<Map<String, Object>>) output.get("embeddings");
                yield (List<Double>) embeddings.get(0).get("embedding");
            }
            case OPENAI -> {
                // {"data": [{"embedding": [...]}]}
                List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                yield (List<Double>) data.get(0).get("embedding");
            }
        };

        float[] arr = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            arr[i] = vec.get(i).floatValue();
        }
        return arr;
    }

    public String getModelName() { return modelName; }
    public int getDimension() { return dimension; }
    public Format getFormat() { return format; }
}
