package com.agentplatform.gateway.rag.chunking;

import com.agentplatform.gateway.llm.LlmRouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档分块器
 * 支持多种分块策略，针对 SOP 文档优化
 */
@Component
@Slf4j
public class DocumentChunker {

    private final EmbeddingModel embeddingModel;
    private final LlmRouterService llmRouterService;

    @Autowired
    public DocumentChunker(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) LlmRouterService llmRouterService) {
        this.embeddingModel = embeddingModel;
        this.llmRouterService = llmRouterService;
    }

    /**
     * 对文档进行分块
     *
     * @param content 文档内容
     * @param documentName 文档名称（用于生成 metadata）
     * @param config 分块配置
     * @return 分块结果列表
     */
    public List<Chunk> chunk(String content, String documentName, ChunkingConfig config) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<Chunk> chunks = switch (config.getStrategy()) {
            case FIXED_SIZE -> chunkFixedSize(content, config);
            case RECURSIVE -> chunkRecursive(content, config);
            case MARKDOWN -> chunkMarkdown(content, config);
            case DOCUMENT_BASED -> chunkDocumentBased(content, config);
            case SEMANTIC -> chunkSemantic(content, config);
            case AGENTIC -> chunkAgentic(content, documentName, config);
        };

        // 确保 chunk 不超过 embedding 模型的 token 限制
        int maxChars = config.getMaxCharsForEmbedding();
        chunks = enforceEmbeddingLimit(chunks, maxChars, config);

        // 后处理：添加文档名称、生成 ID、提取关键词
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            chunk.setIndex(i);
            chunk.setId(generateChunkId(documentName, chunk.getType(), i));
            
            // 添加文档名称到 metadata
            if (chunk.getMetadata() == null) {
                chunk.setMetadata(new HashMap<>());
            }
            chunk.getMetadata().put("document_name", documentName);
            chunk.getMetadata().put("chunk_index", i);
            chunk.getMetadata().put("total_chunks", chunks.size());
            chunk.getMetadata().put("char_count", chunk.getContent().length());
            chunk.getMetadata().put("estimated_tokens", estimateTokens(chunk.getContent(), config));
            // 记录 embedding 模型，确保查询时使用相同模型
            chunk.getMetadata().put("embedding_model", config.getEmbeddingModel());
            
            // 提取关键词
            if (config.isExtractKeywords()) {
                chunk.setKeywords(extractKeywords(chunk.getContent(), config.getMaxKeywords()));
            }
        }

        log.info("Chunked document '{}' into {} chunks using {} strategy (max {} chars/chunk for embedding)", 
            documentName, chunks.size(), config.getStrategy(), maxChars);

        return chunks;
    }

    /**
     * 固定大小切分
     */
    private List<Chunk> chunkFixedSize(String content, ChunkingConfig config) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkSize = config.getChunkSize();
        int overlap = config.getOverlap();
        int start = 0;

        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            String chunkContent = content.substring(start, end);

            chunks.add(Chunk.builder()
                .content(chunkContent)
                .type(Chunk.ChunkType.PARAGRAPH)
                .startOffset(start)
                .endOffset(end)
                .metadata(new HashMap<>())
                .build());

            start = end - overlap;
            if (start >= content.length() - overlap) break;
        }

        return mergeSmallChunks(chunks, config.getMinChunkSize());
    }

    /**
     * 递归切分
     */
    private List<Chunk> chunkRecursive(String content, ChunkingConfig config) {
        return chunkRecursiveInternal(content, config.getSeparators(), 0, config);
    }

    private List<Chunk> chunkRecursiveInternal(String content, List<String> separators, 
                                                int separatorIndex, ChunkingConfig config) {
        if (content.length() <= config.getChunkSize() || separatorIndex >= separators.size()) {
            if (content.isBlank()) return List.of();
            return List.of(Chunk.builder()
                .content(content.trim())
                .type(Chunk.ChunkType.PARAGRAPH)
                .metadata(new HashMap<>())
                .build());
        }

        String separator = separators.get(separatorIndex);
        String[] parts = content.split(Pattern.quote(separator));
        
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String part : parts) {
            if (currentChunk.length() + part.length() + separator.length() <= config.getChunkSize()) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(separator);
                }
                currentChunk.append(part);
            } else {
                if (currentChunk.length() > 0) {
                    chunks.addAll(chunkRecursiveInternal(
                        currentChunk.toString(), separators, separatorIndex + 1, config));
                    currentChunk = new StringBuilder();
                }
                
                if (part.length() > config.getChunkSize()) {
                    chunks.addAll(chunkRecursiveInternal(
                        part, separators, separatorIndex + 1, config));
                } else {
                    currentChunk.append(part);
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.addAll(chunkRecursiveInternal(
                currentChunk.toString(), separators, separatorIndex + 1, config));
        }

        return mergeSmallChunks(chunks, config.getMinChunkSize());
    }

    /**
     * Markdown 结构切分（推荐用于 SOP）
     */
    private List<Chunk> chunkMarkdown(String content, ChunkingConfig config) {
        List<Chunk> chunks = new ArrayList<>();
        
        // 正则匹配 Markdown 标题
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Pattern stepPattern = Pattern.compile(config.getStepPattern(), Pattern.MULTILINE);
        Pattern faqPattern = Pattern.compile(config.getFaqPattern(), Pattern.MULTILINE);
        
        // 按标题切分
        Matcher matcher = headingPattern.matcher(content);
        List<int[]> headingPositions = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        List<String> headingTitles = new ArrayList<>();
        
        while (matcher.find()) {
            headingPositions.add(new int[]{matcher.start(), matcher.end()});
            headingLevels.add(matcher.group(1).length());
            headingTitles.add(matcher.group(2).trim());
        }

        // 提取文档标题（第一个 # 标题）
        String documentTitle = "";
        if (!headingTitles.isEmpty() && headingLevels.get(0) == 1) {
            documentTitle = headingTitles.get(0);
        }

        // 按标题位置切分内容
        for (int i = 0; i < headingPositions.size(); i++) {
            int start = headingPositions.get(i)[0];
            int end = (i + 1 < headingPositions.size()) 
                ? headingPositions.get(i + 1)[0] 
                : content.length();
            
            String section = content.substring(start, end).trim();
            if (section.isBlank()) continue;

            // 判断 chunk 类型
            Chunk.ChunkType type = Chunk.ChunkType.PARAGRAPH;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("heading_level", headingLevels.get(i));
            metadata.put("heading_title", headingTitles.get(i));
            
            if (!documentTitle.isEmpty()) {
                metadata.put("sop_name", documentTitle);
            }

            // 检查是否是步骤
            Matcher stepMatcher = stepPattern.matcher(section);
            if (stepMatcher.find()) {
                type = Chunk.ChunkType.STEP;
                try {
                    int stepNumber = Integer.parseInt(stepMatcher.group(1));
                    metadata.put("step_number", stepNumber);
                    metadata.put("chunk_type", "step");
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
            
            // 检查是否是 FAQ
            Matcher faqMatcher = faqPattern.matcher(section);
            if (faqMatcher.find()) {
                type = Chunk.ChunkType.FAQ;
                metadata.put("chunk_type", "faq");
                // 提取问题
                String question = headingTitles.get(i).replaceFirst("^Q[:：]\\s*", "");
                metadata.put("question", question);
            }

            // 检查是否是概述
            String lowerTitle = headingTitles.get(i).toLowerCase();
            if (lowerTitle.contains("概述") || lowerTitle.contains("overview") || 
                lowerTitle.contains("简介") || lowerTitle.contains("introduction")) {
                type = Chunk.ChunkType.OVERVIEW;
                metadata.put("chunk_type", "overview");
            }

            // 检查是否是附录
            if (lowerTitle.contains("附录") || lowerTitle.contains("appendix")) {
                type = Chunk.ChunkType.APPENDIX;
                metadata.put("chunk_type", "appendix");
            }

            // 如果 chunk 太大，递归切分
            if (section.length() > config.getMaxChunkSize()) {
                List<Chunk> subChunks = chunkRecursive(section, config);
                for (Chunk subChunk : subChunks) {
                    subChunk.setType(type);
                    subChunk.getMetadata().putAll(metadata);
                }
                chunks.addAll(subChunks);
            } else {
                chunks.add(Chunk.builder()
                    .content(section)
                    .type(type)
                    .startOffset(start)
                    .endOffset(end)
                    .metadata(metadata)
                    .build());
            }
        }

        // 如果没有找到标题，使用递归切分
        if (chunks.isEmpty()) {
            return chunkRecursive(content, config);
        }

        return mergeSmallChunks(chunks, config.getMinChunkSize());
    }

    /**
     * 基于文档结构切分
     */
    private List<Chunk> chunkDocumentBased(String content, ChunkingConfig config) {
        // 对于 Markdown 文档，使用 Markdown 切分
        if (content.contains("#")) {
            return chunkMarkdown(content, config);
        }
        // 否则使用递归切分
        return chunkRecursive(content, config);
    }

    /**
     * 语义切分（完整实现）
     * 基于 Embedding 相似度切分：相邻句子相似度低于阈值时切分
     */
    private List<Chunk> chunkSemantic(String content, ChunkingConfig config) {
        // 按句子切分
        String[] sentences = content.split("(?<=[。！？.!?])\\s*");
        if (sentences.length == 0) {
            return List.of();
        }

        // 如果没有 Embedding 模型，回退到简单实现
        if (embeddingModel == null) {
            log.warn("EmbeddingModel not available, falling back to simple sentence merging");
            return chunkSemanticSimple(sentences, config);
        }

        try {
            // 获取所有句子的 Embedding
            List<String> sentenceList = Arrays.asList(sentences);
            List<float[]> embeddings = embeddingModel.embed(sentenceList);

            // 计算相邻句子的相似度
            double[] similarities = new double[sentences.length - 1];
            for (int i = 0; i < sentences.length - 1; i++) {
                similarities[i] = cosineSimilarity(embeddings.get(i), embeddings.get(i + 1));
            }

            // 找出相似度低于阈值的切分点
            double threshold = config.getSemanticThreshold();
            List<Integer> splitPoints = new ArrayList<>();
            splitPoints.add(0);
            for (int i = 0; i < similarities.length; i++) {
                if (similarities[i] < threshold) {
                    splitPoints.add(i + 1);
                }
            }
            splitPoints.add(sentences.length);

            // 按切分点生成 chunks
            List<Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < splitPoints.size() - 1; i++) {
                int start = splitPoints.get(i);
                int end = splitPoints.get(i + 1);
                
                StringBuilder sb = new StringBuilder();
                for (int j = start; j < end; j++) {
                    sb.append(sentences[j]);
                }
                
                String chunkContent = sb.toString().trim();
                if (!chunkContent.isEmpty()) {
                    // 如果 chunk 太大，递归切分
                    if (chunkContent.length() > config.getMaxChunkSize()) {
                        chunks.addAll(chunkRecursive(chunkContent, config));
                    } else {
                        chunks.add(Chunk.builder()
                            .content(chunkContent)
                            .type(Chunk.ChunkType.PARAGRAPH)
                            .metadata(new HashMap<>())
                            .build());
                    }
                }
            }

            return mergeSmallChunks(chunks, config.getMinChunkSize());

        } catch (Exception e) {
            log.warn("Semantic chunking failed, falling back to simple: {}", e.getMessage());
            return chunkSemanticSimple(sentences, config);
        }
    }

    /**
     * 简单语义切分（无 Embedding 模型时的回退方案）
     */
    private List<Chunk> chunkSemanticSimple(String[] sentences, ChunkingConfig config) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() <= config.getChunkSize()) {
                currentChunk.append(sentence);
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(Chunk.builder()
                        .content(currentChunk.toString().trim())
                        .type(Chunk.ChunkType.PARAGRAPH)
                        .metadata(new HashMap<>())
                        .build());
                }
                currentChunk = new StringBuilder(sentence);
            }
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(Chunk.builder()
                .content(currentChunk.toString().trim())
                .type(Chunk.ChunkType.PARAGRAPH)
                .metadata(new HashMap<>())
                .build());
        }

        return mergeSmallChunks(chunks, config.getMinChunkSize());
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        if (normA == 0 || normB == 0) return 0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Agentic Chunking（LLM 辅助切分）
     * 使用 LLM 分析文档结构，智能切分
     */
    private List<Chunk> chunkAgentic(String content, String documentName, ChunkingConfig config) {
        if (llmRouterService == null) {
            log.warn("LlmRouterService not available, falling back to document-based chunking");
            return chunkDocumentBased(content, config);
        }

        try {
            // 构建 Prompt
            String systemPrompt = """
                你是一个文档分析专家。请分析以下文档，将其切分为独立的知识单元。
                
                切分原则：
                1. 每个单元应该能独立回答一个问题
                2. 保持语义完整，不要在句子中间切分
                3. 每个单元大小在 100-800 字符之间
                4. 识别文档结构：概述、步骤、FAQ、附录等
                
                请以 JSON 格式输出切分结果，格式如下：
                ```json
                [
                  {
                    "start": 0,
                    "end": 150,
                    "type": "overview",
                    "title": "概述"
                  },
                  {
                    "start": 151,
                    "end": 400,
                    "type": "step",
                    "step_number": 1,
                    "title": "步骤1标题"
                  }
                ]
                ```
                
                type 可选值：overview, step, faq, appendix, paragraph
                只输出 JSON，不要其他内容。
                """;

            String userPrompt = "文档名称：" + documentName + "\n\n文档内容：\n" + content;

            // 调用 LLM
            Map<String, Object> result = llmRouterService.chat(
                null, // 使用系统身份
                config.getAgenticModel() != null ? config.getAgenticModel() : "default",
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                0.3, // 低温度，保持稳定
                2000
            );

            String response = (String) result.get("content");
            
            // 解析 JSON 响应
            List<Chunk> chunks = parseAgenticResponse(response, content, documentName);
            
            if (chunks.isEmpty()) {
                log.warn("Agentic chunking returned empty result, falling back");
                return chunkDocumentBased(content, config);
            }

            return chunks;

        } catch (Exception e) {
            log.warn("Agentic chunking failed: {}, falling back to document-based", e.getMessage());
            return chunkDocumentBased(content, config);
        }
    }

    /**
     * 解析 LLM 的 Agentic Chunking 响应
     */
    @SuppressWarnings("unchecked")
    private List<Chunk> parseAgenticResponse(String response, String content, String documentName) {
        List<Chunk> chunks = new ArrayList<>();
        
        try {
            // 提取 JSON 部分
            String json = response;
            if (response.contains("```json")) {
                int start = response.indexOf("```json") + 7;
                int end = response.indexOf("```", start);
                if (end > start) {
                    json = response.substring(start, end).trim();
                }
            } else if (response.contains("```")) {
                int start = response.indexOf("```") + 3;
                int end = response.indexOf("```", start);
                if (end > start) {
                    json = response.substring(start, end).trim();
                }
            }

            // 使用简单的 JSON 解析（避免引入额外依赖）
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> chunkDefs = mapper.readValue(json, List.class);

            for (Map<String, Object> def : chunkDefs) {
                int start = ((Number) def.get("start")).intValue();
                int end = Math.min(((Number) def.get("end")).intValue(), content.length());
                
                if (start >= end || start < 0 || end > content.length()) continue;

                String chunkContent = content.substring(start, end).trim();
                if (chunkContent.isEmpty()) continue;

                String typeStr = (String) def.getOrDefault("type", "paragraph");
                Chunk.ChunkType type = switch (typeStr.toLowerCase()) {
                    case "overview" -> Chunk.ChunkType.OVERVIEW;
                    case "step" -> Chunk.ChunkType.STEP;
                    case "faq" -> Chunk.ChunkType.FAQ;
                    case "appendix" -> Chunk.ChunkType.APPENDIX;
                    default -> Chunk.ChunkType.PARAGRAPH;
                };

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sop_name", documentName);
                metadata.put("chunk_type", typeStr);
                
                if (def.containsKey("title")) {
                    metadata.put("title", def.get("title"));
                }
                if (def.containsKey("step_number")) {
                    metadata.put("step_number", ((Number) def.get("step_number")).intValue());
                }

                chunks.add(Chunk.builder()
                    .content(chunkContent)
                    .type(type)
                    .startOffset(start)
                    .endOffset(end)
                    .metadata(metadata)
                    .build());
            }

        } catch (Exception e) {
            log.warn("Failed to parse agentic response: {}", e.getMessage());
        }

        return chunks;
    }

    /**
     * 合并过小的 chunk
     */
    private List<Chunk> mergeSmallChunks(List<Chunk> chunks, int minSize) {
        if (chunks.size() <= 1) return chunks;

        List<Chunk> merged = new ArrayList<>();
        Chunk current = null;

        for (Chunk chunk : chunks) {
            if (current == null) {
                current = chunk;
            } else if (current.getContent().length() < minSize) {
                // 合并到当前 chunk
                current.setContent(current.getContent() + "\n\n" + chunk.getContent());
                current.setEndOffset(chunk.getEndOffset());
            } else {
                merged.add(current);
                current = chunk;
            }
        }

        if (current != null) {
            merged.add(current);
        }

        return merged;
    }

    /**
     * 生成 Chunk ID
     */
    private String generateChunkId(String documentName, Chunk.ChunkType type, int index) {
        String sanitizedName = documentName.toLowerCase()
            .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        
        return String.format("%s-%s-%03d", sanitizedName, type.name().toLowerCase(), index);
    }

    /**
     * 提取关键词（简单实现）
     */
    private List<String> extractKeywords(String content, int maxKeywords) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        // 停用词
        Set<String> stopWords = Set.of(
            "的", "是", "在", "了", "和", "与", "或", "等", "及", "到", "为", "被", "把", "让",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "may", "might", "must", "shall", "can", "need", "dare", "ought", "used",
            "to", "of", "in", "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "during", "before", "after", "above", "below", "between", "under"
        );

        // 分词并统计词频
        Map<String, Integer> wordFreq = new HashMap<>();
        String[] words = content.split("[\\s\\p{Punct}]+");
        
        for (String word : words) {
            word = word.trim().toLowerCase();
            if (word.length() >= 2 && !stopWords.contains(word)) {
                wordFreq.merge(word, 1, Integer::sum);
            }
        }

        // 按词频排序，取前 N 个
        return wordFreq.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(maxKeywords)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * 确保所有 chunk 不超过 embedding 模型的 token 限制
     * 超过限制的 chunk 会被递归切分
     */
    private List<Chunk> enforceEmbeddingLimit(List<Chunk> chunks, int maxChars, ChunkingConfig config) {
        List<Chunk> result = new ArrayList<>();
        
        for (Chunk chunk : chunks) {
            if (chunk.getContent().length() <= maxChars) {
                result.add(chunk);
            } else {
                // 超过限制，需要切分
                log.debug("Chunk exceeds embedding limit ({} > {}), splitting...", 
                    chunk.getContent().length(), maxChars);
                
                List<Chunk> subChunks = splitChunkForEmbedding(chunk, maxChars, config);
                result.addAll(subChunks);
            }
        }
        
        return result;
    }

    /**
     * 将超大 chunk 切分为符合 embedding 限制的小 chunk
     */
    private List<Chunk> splitChunkForEmbedding(Chunk chunk, int maxChars, ChunkingConfig config) {
        String content = chunk.getContent();
        List<Chunk> subChunks = new ArrayList<>();
        
        // 尝试按句子切分
        String[] sentences = content.split("(?<=[。！？.!?])\\s*");
        StringBuilder currentContent = new StringBuilder();
        
        for (String sentence : sentences) {
            // 单个句子就超过限制，强制按字符切分
            if (sentence.length() > maxChars) {
                // 先保存当前累积的内容
                if (currentContent.length() > 0) {
                    subChunks.add(createSubChunk(chunk, currentContent.toString()));
                    currentContent = new StringBuilder();
                }
                // 强制切分长句子
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    int end = Math.min(i + maxChars, sentence.length());
                    subChunks.add(createSubChunk(chunk, sentence.substring(i, end)));
                }
            } else if (currentContent.length() + sentence.length() > maxChars) {
                // 加上这个句子会超限，先保存当前内容
                if (currentContent.length() > 0) {
                    subChunks.add(createSubChunk(chunk, currentContent.toString()));
                }
                currentContent = new StringBuilder(sentence);
            } else {
                currentContent.append(sentence);
            }
        }
        
        // 保存剩余内容
        if (currentContent.length() > 0) {
            subChunks.add(createSubChunk(chunk, currentContent.toString()));
        }
        
        return subChunks;
    }

    /**
     * 创建子 chunk，继承父 chunk 的类型和 metadata
     */
    private Chunk createSubChunk(Chunk parent, String content) {
        Map<String, Object> metadata = new HashMap<>();
        if (parent.getMetadata() != null) {
            metadata.putAll(parent.getMetadata());
        }
        metadata.put("split_from_oversized", true);
        
        return Chunk.builder()
            .content(content.trim())
            .type(parent.getType())
            .metadata(metadata)
            .build();
    }

    /**
     * 估算文本的 token 数量
     */
    private int estimateTokens(String content, ChunkingConfig config) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(content.length() / config.getCharsPerToken());
    }
}
