package com.agentplatform.gateway.rag.component;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 语义改写服务
 * 提供自适应的查询改写能力，根据历史上下文和查询类型自动选择改写策略
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SemanticRewriteService {

    private final LlmRouterService llmRouterService;

    /**
     * 执行语义改写
     * 
     * @param identity 调用者身份
     * @param request 改写请求
     * @return 改写结果
     */
    public SemanticRewriteResult rewrite(CallerIdentity identity, SemanticRewriteRequest request) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(request);

            Map<String, Object> result = llmRouterService.chat(
                identity,
                request.getModel(),
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                0.3, // 低温度，保持稳定
                1000 // 限制输出长度
            );

            String content = (String) result.get("content");
            if (content != null && !content.isBlank()) {
                return parseRewriteResult(content);
            }
        } catch (Exception e) {
            log.warn("Semantic rewrite failed, using original query: {}", e.getMessage());
        }
        
        // 降级处理：返回原始查询
        return SemanticRewriteResult.builder()
            .mode(RewriteMode.NONE)
            .finalQuery(request.getCurrentQuery())
            .rewriteReason("改写失败，使用原始查询")
            .build();
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return """
            你是一个专业的查询语义改写专家。你的任务是根据用户的历史对话和当前问题，自动识别改写场景并执行相应的语义改写。
            
            ## 改写场景识别与处理规则：
            
            ### 1. 指代消解补全场景
            **识别特征**：当前查询包含代词（它、这个、那个、上述、前者等）或指代性表达
            **处理方式**：结合历史上下文，将指代替换为具体的实体或概念
            **输出模式**：deixis
            
            ### 2. 复合问题拆解场景  
            **识别特征**：当前查询包含多个问题、多个条件或复杂逻辑关系（和、或、但是、以及等）
            **处理方式**：将复合问题拆解为多个独立的子问题
            **输出模式**：decomposition
            
            ### 3. 表达标准化场景
            **识别特征**：当前查询包含口语化表达、同义词、术语不统一等问题
            **处理方式**：统一术语、扩展同义词、标准化表达
            **输出模式**：normalization
            
            ### 4. 简单查询场景
            **识别特征**：当前查询已经足够清晰、具体，无需改写
            **处理方式**：直接返回原查询
            **输出模式**：none
            
            ## 输出格式要求：
            请严格按照以下JSON格式输出，不要添加任何解释或额外文字：
            
            ```json
            {
              "mode": "deixis|decomposition|normalization|none",
              "final_query": "最终的单个优化查询",
              "sub_queries": ["子查询1", "子查询2", ...],
              "clarified_query": "澄清后的查询（指代消解场景）",
              "rewrite_reason": "改写原因说明",
              "confidence": 0.95
            }
            ```
            
            ## 字段说明：
            - mode: 改写模式，必须为四个值之一
            - final_query: 最终用于检索的查询（所有模式都必须提供）
            - sub_queries: 子查询列表（仅在decomposition模式时使用）
            - clarified_query: 澄清后的查询（仅在deixis模式时使用）
            - rewrite_reason: 改写原因的简要说明
            - confidence: 改写结果的置信度（0-1之间的数值）
            """;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(SemanticRewriteRequest request) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("## 改写请求信息\n\n");
        
        // 当前查询
        prompt.append("当前查询：").append(request.getCurrentQuery()).append("\n\n");
        
        // 历史对话
        if (request.getHistory() != null && !request.getHistory().isEmpty()) {
            prompt.append("历史对话：\n");
            for (int i = 0; i < request.getHistory().size(); i++) {
                HistoryEntry entry = request.getHistory().get(i);
                prompt.append(i + 1).append(". ");
                if (entry.getRole() != null) {
                    prompt.append("[").append(entry.getRole()).append("] ");
                }
                prompt.append(entry.getContent()).append("\n");
            }
            prompt.append("\n");
        }
        
        // 集合信息
        if (request.getCollection() != null) {
            prompt.append("目标知识库：").append(request.getCollection()).append("\n\n");
        }
        
        // 用户画像（如果有）
        if (request.getUserProfile() != null && !request.getUserProfile().isEmpty()) {
            prompt.append("用户画像信息：\n");
            request.getUserProfile().forEach((key, value) -> 
                prompt.append("- ").append(key).append(": ").append(value).append("\n"));
            prompt.append("\n");
        }
        
        prompt.append("请根据上述信息，识别改写场景并执行相应的语义改写。");
        
        return prompt.toString();
    }

    /**
     * 解析改写结果
     */
    private SemanticRewriteResult parseRewriteResult(String content) {
        try {
            // 提取JSON部分
            int jsonStart = content.indexOf("{");
            int jsonEnd = content.lastIndexOf("}");
            
            if (jsonStart == -1 || jsonEnd == -1) {
                throw new IllegalArgumentException("Invalid JSON format in response");
            }
            
            // 简化的JSON解析（实际项目中应使用ObjectMapper）
            // 这里暂时返回一个基于内容的简化结果
            RewriteMode mode = detectModeFromContent(content);
            String finalQuery = extractFinalQuery(content);
            String reason = extractRewriteReason(content);
            
            return SemanticRewriteResult.builder()
                .mode(mode)
                .finalQuery(finalQuery)
                .rewriteReason(reason)
                .confidence(0.8)
                .build();
                
        } catch (Exception e) {
            log.warn("Failed to parse rewrite result: {}", e.getMessage());
            throw new RuntimeException("Failed to parse rewrite result", e);
        }
    }
    
    /**
     * 从内容中检测改写模式
     */
    private RewriteMode detectModeFromContent(String content) {
        if (content.contains("deixis") || content.contains("指代")) {
            return RewriteMode.DEIXIS;
        } else if (content.contains("decomposition") || content.contains("拆解")) {
            return RewriteMode.DECOMPOSITION;
        } else if (content.contains("normalization") || content.contains("标准化")) {
            return RewriteMode.NORMALIZATION;
        } else {
            return RewriteMode.NONE;
        }
    }
    
    /**
     * 提取最终查询
     */
    private String extractFinalQuery(String content) {
        // 简化的提取逻辑，实际应使用JSON解析
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.contains("final_query") || line.contains("澄清后")) {
                int colonIndex = line.indexOf(":");
                if (colonIndex > 0) {
                    String query = line.substring(colonIndex + 1).trim();
                    // 去除引号和逗号
                    query = query.replaceAll("[\"',]", "").trim();
                    if (!query.isEmpty()) {
                        return query;
                    }
                }
            }
        }
        return "查询解析失败";
    }
    
    /**
     * 提取改写原因
     */
    private String extractRewriteReason(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.contains("rewrite_reason") || line.contains("原因")) {
                int colonIndex = line.indexOf(":");
                if (colonIndex > 0) {
                    String reason = line.substring(colonIndex + 1).trim();
                    reason = reason.replaceAll("[\"',]", "").trim();
                    if (!reason.isEmpty()) {
                        return reason;
                    }
                }
            }
        }
        return "语义改写完成";
    }

    /**
     * 语义改写请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SemanticRewriteRequest {
        /**
         * 当前查询
         */
        private String currentQuery;
        
        /**
         * 历史对话
         */
        private List<HistoryEntry> history;
        
        /**
         * 目标集合
         */
        private String collection;
        
        /**
         * LLM模型
         */
        @lombok.Builder.Default
        private String model = "default";
        
        /**
         * 用户画像
         */
        private Map<String, Object> userProfile;
    }

    /**
     * 历史对话条目
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HistoryEntry {
        /**
         * 角色（user/assistant）
         */
        private String role;
        
        /**
         * 内容
         */
        private String content;
        
        /**
         * 时间戳
         */
        private Long timestamp;
    }

    /**
     * 语义改写结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SemanticRewriteResult {
        /**
         * 改写模式
         */
        private RewriteMode mode;
        
        /**
         * 最终查询（用于检索）
         */
        private String finalQuery;
        
        /**
         * 子查询列表（复合问题拆解）
         */
        private List<String> subQueries;
        
        /**
         * 澄清后的查询（指代消解）
         */
        private String clarifiedQuery;
        
        /**
         * 改写原因
         */
        private String rewriteReason;
        
        /**
         * 置信度
         */
        private Double confidence;
    }

    /**
     * 改写模式枚举
     */
    public enum RewriteMode {
        /**
         * 指代消解
         */
        DEIXIS("deixis", "指代消解补全"),
        
        /**
         * 复合问题拆解
         */
        DECOMPOSITION("decomposition", "复合问题拆解"),
        
        /**
         * 表达标准化
         */
        NORMALIZATION("normalization", "表达标准化"),
        
        /**
         * 无需改写
         */
        NONE("none", "简单查询");
        
        private final String code;
        private final String description;
        
        RewriteMode(String code, String description) {
            this.code = code;
            this.description = description;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getDescription() {
            return description;
        }
    }
}
