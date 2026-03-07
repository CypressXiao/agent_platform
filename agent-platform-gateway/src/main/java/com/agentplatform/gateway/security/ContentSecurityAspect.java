package com.agentplatform.gateway.security;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内容安全切面
 * 在 LLM 调用和工具调用时自动进行内容安全检查
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentSecurityAspect {

    private final ContentSecurityService securityService;

    /**
     * 拦截 LLM 调用，检查输入内容
     */
    @Around("execution(* com.agentplatform.gateway.llm.LlmRouterService.chat(..))")
    public Object filterLlmInput(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();

        // 检查 messages 参数中的内容
        if (args.length >= 3 && args[2] instanceof java.util.List<?> messages) {
            for (Object msg : messages) {
                if (msg instanceof Map<?, ?> msgMap) {
                    Object content = msgMap.get("content");
                    if (content instanceof String contentStr) {
                        ContentFilter result = securityService.filterInput(contentStr);
                        if (!result.isPassed()) {
                            log.warn("LLM input blocked: reason={}, riskLevel={}",
                                result.getReason(), result.getRiskLevel());
                            throw new McpException(McpErrorCode.LLM_PROMPT_FILTERED,
                                "Content blocked: " + result.getReason());
                        }
                    }
                }
            }
        }

        // 执行原方法
        Object result = joinPoint.proceed();

        // 检查输出内容
        if (result instanceof Map<?, ?> resultMap) {
            Object content = resultMap.get("content");
            if (content instanceof String contentStr) {
                ContentFilter outputFilter = securityService.filterOutput(contentStr);
                if (!outputFilter.isPassed()) {
                    log.warn("LLM output blocked: reason={}", outputFilter.getReason());
                    throw new McpException(McpErrorCode.LLM_PROMPT_FILTERED,
                        "Output blocked: " + outputFilter.getReason());
                }
                // 如果有脱敏内容，替换原内容
                if (outputFilter.getSanitizedContent() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mutableResult = (Map<String, Object>) resultMap;
                    mutableResult.put("content", outputFilter.getSanitizedContent());
                    mutableResult.put("_pii_sanitized", true);
                }
            }
        }

        return result;
    }

    /**
     * 拦截工具调用，检查参数内容
     */
    @Around("execution(* com.agentplatform.gateway.mcp.router.ToolDispatcher.dispatch(..))")
    public Object filterToolInput(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();

        // 检查 arguments 参数
        if (args.length >= 3 && args[2] instanceof Map<?, ?> arguments) {
            for (Object value : arguments.values()) {
                if (value instanceof String strValue) {
                    ContentFilter result = securityService.filterInput(strValue);
                    if (!result.isPassed() && 
                        result.getRiskLevel() == ContentFilter.RiskLevel.CRITICAL) {
                        log.warn("Tool input blocked: reason={}", result.getReason());
                        throw new McpException(McpErrorCode.BAD_REQUEST,
                            "Content blocked: " + result.getReason());
                    }
                }
            }
        }

        return joinPoint.proceed();
    }
}
