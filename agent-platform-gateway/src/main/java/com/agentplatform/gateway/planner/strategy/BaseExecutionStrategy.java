package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行策略抽象基类 - 提供默认实现和钩子方法
 * 
 * SDK 用户可以：
 * 1. 直接使用内置策略（开箱即用）
 * 2. 继承此类覆盖钩子方法（灵活定制）
 * 3. 直接实现 ExecutionStrategy 接口（完全自定义）
 */
@Slf4j
public abstract class BaseExecutionStrategy implements ExecutionStrategy {

    protected final ToolDispatcher toolDispatcher;
    protected final ToolAggregator toolAggregator;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    public BaseExecutionStrategy(ToolDispatcher toolDispatcher, ToolAggregator toolAggregator) {
        this.toolDispatcher = toolDispatcher;
        this.toolAggregator = toolAggregator;
    }

    // ==================== 钩子方法 - 子类可覆盖 ====================

    /**
     * 构建 System Prompt
     * 
     * @param tools 可用工具列表（包含名称和描述）
     * @param context 执行上下文
     * @return System Prompt
     */
    protected String buildSystemPrompt(List<ToolInfo> tools, PlanContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI assistant that helps users accomplish tasks.\n\n");
        sb.append("Available tools:\n");
        for (ToolInfo tool : tools) {
            sb.append("- ").append(tool.name());
            if (tool.description() != null) {
                sb.append(": ").append(tool.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建 User Prompt
     * 
     * @param goal 目标
     * @param context 执行上下文
     * @return User Prompt
     */
    protected String buildUserPrompt(String goal, PlanContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal).append("\n\n");
        
        // 添加记忆上下文
        if (!context.getMemories().isEmpty()) {
            sb.append("Relevant memories:\n");
            for (Map<String, Object> memory : context.getMemories()) {
                sb.append("- ").append(memory.get("content")).append("\n");
            }
            sb.append("\n");
        }
        
        // 添加状态上下文
        if (!context.getState().isEmpty()) {
            sb.append("Current context: ").append(context.getState()).append("\n\n");
        }
        
        return sb.toString();
    }

    /**
     * 查询相关记忆
     * 
     * 默认实现：
     * - 新会话：查询长期记忆（语义）
     * - 继续会话：查询短期记忆 + 长期记忆
     * 
     * @param identity 调用方身份
     * @param goal 目标
     * @param context 执行上下文
     * @return 相关记忆列表
     */
    protected List<Map<String, Object>> queryMemories(CallerIdentity identity, String goal, PlanContext context) {
        // 默认不查询记忆，子类可覆盖
        // 这样做是因为 Memory 模块是可选的，不是所有部署都有
        return List.of();
    }

    /**
     * 保存执行摘要到记忆
     * 
     * 默认实现为空（不保存），子类可覆盖实现自定义保存逻辑。
     * PlanningEngine 会在执行完成后调用此方法作为兜底。
     * 
     * @param identity 调用方身份
     * @param goal 目标
     * @param context 执行上下文
     * @param success 是否成功
     */
    public void saveToMemory(CallerIdentity identity, String goal, PlanContext context, boolean success) {
        // 默认不保存，子类可覆盖
        // 示例实现：
        // if (success) {
        //     String summary = buildExecutionSummary(goal, context);
        //     toolDispatcher.dispatchInternal(identity, "memory_save", Map.of(
        //         "type", "long",
        //         "content", summary
        //     ));
        // }
    }

    /**
     * 获取 LLM 模型名称
     */
    protected String getLlmModel(PlanContext context) {
        return "default";
    }

    /**
     * 获取 LLM 温度参数
     */
    protected double getLlmTemperature(PlanContext context) {
        return 0.3;
    }

    /**
     * 解析 LLM 响应
     * 
     * @param llmOutput LLM 输出
     * @return 解析后的结果
     */
    protected Map<String, Object> parseLlmResponse(String llmOutput) {
        try {
            String json = llmOutput.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return Map.of("error", "Parse failed", "raw", llmOutput);
        }
    }

    /**
     * 解析步骤列表
     */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> parseStepsList(String llmOutput) {
        try {
            String json = llmOutput;
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse steps list: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 处理规划失败
     * 
     * @param goal 目标
     * @param error 错误
     * @param context 上下文
     * @return Fallback 步骤
     */
    protected List<Map<String, Object>> onPlanFailed(String goal, Exception error, PlanContext context) {
        log.warn("Planning failed: {}", error.getMessage());
        return List.of(Map.of(
            "description", "Execute goal: " + goal,
            "tool", "echo",
            "arguments", Map.of("message", "Planning failed: " + error.getMessage()),
            "status", "PENDING"
        ));
    }

    /**
     * 处理步骤执行失败
     */
    protected StepResult onStepExecutionFailed(Map<String, Object> step, Exception error, long latencyMs) {
        return StepResult.failed(error.getMessage(), latencyMs);
    }

    // ==================== 通用实现 ====================

    /**
     * 获取工具信息列表（包含描述）
     */
    protected List<ToolInfo> getToolInfos(CallerIdentity identity) {
        return toolAggregator.listTools(identity).stream()
            .map(t -> new ToolInfo(t.name(), t.description(), t.inputSchema()))
            .toList();
    }

    /**
     * 调用 LLM
     */
    protected String callLlm(CallerIdentity identity, String systemPrompt, String userPrompt, PlanContext context) {
        Map<String, Object> llmArgs = Map.of(
            "model", getLlmModel(context),
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "temperature", getLlmTemperature(context)
        );

        Object llmResult = toolDispatcher.dispatchInternal(identity, "llm_chat", llmArgs);

        if (llmResult instanceof Map<?, ?> resultMap) {
            return (String) resultMap.get("content");
        }
        return null;
    }

    /**
     * 执行工具调用
     */
    protected Object executeTool(CallerIdentity identity, String toolName, Map<String, Object> args) {
        return toolDispatcher.dispatchInternal(identity, toolName, args);
    }

    /**
     * 解析参数中的上下文引用
     */
    protected Map<String, Object> resolveArguments(Map<String, Object> args, PlanContext context) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String ref) {
                if (ref.startsWith("$context.")) {
                    String key = ref.substring("$context.".length());
                    resolved.put(entry.getKey(), context.getState().getOrDefault(key, ref));
                } else if (ref.startsWith("$step_")) {
                    resolved.put(entry.getKey(), context.getState().getOrDefault(ref.substring(1), ref));
                } else if (ref.startsWith("$memory.")) {
                    String key = ref.substring("$memory.".length());
                    resolved.put(entry.getKey(), findInMemories(context.getMemories(), key));
                } else {
                    resolved.put(entry.getKey(), value);
                }
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private Object findInMemories(List<Map<String, Object>> memories, String key) {
        for (Map<String, Object> memory : memories) {
            if (memory.containsKey(key)) {
                return memory.get(key);
            }
        }
        return "$memory." + key;
    }

    // ==================== 工具信息记录 ====================

    /**
     * 工具信息（包含描述和 Schema）
     */
    public record ToolInfo(String name, String description, Map<String, Object> inputSchema) {}
}
