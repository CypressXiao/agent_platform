package com.agentplatform.gateway.rag.component;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 查询改写器
 * 使用 LLM 优化用户查询，提高检索准确率
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class QueryRewriter {

    private final LlmRouterService llmRouterService;

    /**
     * 改写查询
     * 
     * @param identity 调用者身份
     * @param originalQuery 原始查询
     * @param model LLM 模型
     * @return 改写后的查询
     */
    public String rewrite(CallerIdentity identity, String originalQuery, String model) {
        try {
            String systemPrompt = """
                你是一个查询优化专家。你的任务是将用户的口语化问题改写为更适合知识库检索的查询。
                
                改写规则：
                1. 提取关键词和核心概念
                2. 去除口语化表达（如"请问"、"我想知道"）
                3. 补充可能的同义词或相关术语
                4. 保持查询简洁，不超过50字
                5. 只输出改写后的查询，不要解释
                
                示例：
                输入：请问一下员工入职的时候需要准备什么材料啊？
                输出：员工入职 所需材料 资料准备 入职流程
                """;

            String userPrompt = "请改写以下查询：" + originalQuery;

            Map<String, Object> result = llmRouterService.chat(
                identity,
                model,
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                0.3, // 低温度，保持稳定
                50   // 限制输出长度
            );

            String rewritten = (String) result.get("content");
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query rewrite failed, using original query: {}", e.getMessage());
        }
        
        return originalQuery;
    }

    /**
     * 多查询扩展（HyDE 变体）
     * 生成多个相关查询，扩大检索范围
     */
    public List<String> expand(CallerIdentity identity, String originalQuery, String model, int count) {
        try {
            String systemPrompt = String.format("""
                你是一个查询扩展专家。请根据用户的问题，生成 %d 个相关但不同角度的查询。
                
                要求：
                1. 每个查询一行
                2. 覆盖不同的表达方式和角度
                3. 保持与原问题相关
                4. 只输出查询，不要编号和解释
                """, count);

            String userPrompt = "原问题：" + originalQuery;

            Map<String, Object> result = llmRouterService.chat(
                identity,
                model,
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                0.7,
                200
            );

            String content = (String) result.get("content");
            if (content != null && !content.isBlank()) {
                return List.of(content.split("\n"))
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(count)
                    .toList();
            }
        } catch (Exception e) {
            log.warn("Query expansion failed: {}", e.getMessage());
        }
        
        return List.of(originalQuery);
    }
}
