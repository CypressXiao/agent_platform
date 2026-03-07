package com.agentplatform.gateway.validation;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JSON Schema 校验器
 * 用于在 ToolDispatcher 入参侧执行工具参数校验，将 4xx 错误前移
 */
@Component
@Slf4j
public class JsonSchemaValidator {

    private final JsonSchemaFactory schemaFactory;
    private final ObjectMapper objectMapper;
    private final Map<String, JsonSchema> schemaCache;

    public JsonSchemaValidator() {
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.objectMapper = new ObjectMapper();
        this.schemaCache = new ConcurrentHashMap<>();
    }

    /**
     * 校验工具参数
     *
     * @param toolName    工具名称（用于错误信息）
     * @param arguments   待校验的参数
     * @param inputSchema 工具的 inputSchema 定义
     * @throws McpException 如果校验失败
     */
    public void validate(String toolName, Map<String, Object> arguments, Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            log.debug("No inputSchema defined for tool '{}', skipping validation", toolName);
            return;
        }

        try {
            // 获取或创建 JsonSchema
            JsonSchema schema = getOrCreateSchema(toolName, inputSchema);

            // 将参数转换为 JsonNode
            JsonNode argumentsNode = objectMapper.valueToTree(arguments != null ? arguments : Map.of());

            // 执行校验
            Set<ValidationMessage> errors = schema.validate(argumentsNode);

            if (!errors.isEmpty()) {
                String errorDetails = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));

                log.warn("JSON Schema validation failed for tool '{}': {}", toolName, errorDetails);

                throw new McpException(
                    McpErrorCode.INVALID_PARAMS,
                    "Invalid arguments for tool '%s': %s".formatted(toolName, errorDetails)
                );
            }

            log.debug("JSON Schema validation passed for tool '{}'", toolName);

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            log.error("JSON Schema validation error for tool '{}': {}", toolName, e.getMessage());
            throw new McpException(
                McpErrorCode.INVALID_PARAMS,
                "Failed to validate arguments for tool '%s': %s".formatted(toolName, e.getMessage())
            );
        }
    }

    /**
     * 获取或创建 JsonSchema（带缓存）
     */
    private JsonSchema getOrCreateSchema(String toolName, Map<String, Object> inputSchema) {
        String cacheKey = toolName + ":" + inputSchema.hashCode();

        return schemaCache.computeIfAbsent(cacheKey, key -> {
            try {
                JsonNode schemaNode = objectMapper.valueToTree(inputSchema);
                return schemaFactory.getSchema(schemaNode);
            } catch (Exception e) {
                log.error("Failed to create JsonSchema for tool '{}': {}", toolName, e.getMessage());
                throw new RuntimeException("Invalid inputSchema for tool: " + toolName, e);
            }
        });
    }

    /**
     * 清除指定工具的 schema 缓存
     */
    public void invalidateCache(String toolName) {
        schemaCache.entrySet().removeIf(entry -> entry.getKey().startsWith(toolName + ":"));
        log.debug("Invalidated schema cache for tool '{}'", toolName);
    }

    /**
     * 清除所有 schema 缓存
     */
    public void clearCache() {
        schemaCache.clear();
        log.debug("Cleared all schema cache");
    }
}
