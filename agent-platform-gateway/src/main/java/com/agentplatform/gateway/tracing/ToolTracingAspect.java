package com.agentplatform.gateway.tracing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 工具调用追踪切面
 * 自动为 BuiltinToolHandler.execute 添加 Span
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolTracingAspect {

    private final TracingService tracingService;

    @Around("execution(* com.agentplatform.gateway.mcp.registry.BuiltinToolHandler.execute(..))")
    public Object traceToolExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String toolName = joinPoint.getTarget().getClass().getSimpleName();
        
        try (TracingService.SpanWrapper span = tracingService.startSpan("tool." + toolName)) {
            span.tag("tool.class", toolName);
            
            try {
                Object result = joinPoint.proceed();
                span.success();
                return result;
            } catch (Throwable e) {
                span.error(e);
                throw e;
            }
        }
    }

    @Around("execution(* com.agentplatform.gateway.llm.LlmRouterService.chat(..))")
    public Object traceLlmCall(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        String model = args.length > 1 ? String.valueOf(args[1]) : "unknown";
        
        try (TracingService.SpanWrapper span = tracingService.startSpan("llm.chat")) {
            span.tag("llm.model", model);
            
            try {
                Object result = joinPoint.proceed();
                span.success();
                return result;
            } catch (Throwable e) {
                span.error(e);
                throw e;
            }
        }
    }

    @Around("execution(* com.agentplatform.gateway.vector.VectorStoreService.*(..))")
    public Object traceVectorOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        
        try (TracingService.SpanWrapper span = tracingService.startSpan("vector." + methodName)) {
            try {
                Object result = joinPoint.proceed();
                span.success();
                return result;
            } catch (Throwable e) {
                span.error(e);
                throw e;
            }
        }
    }
}
