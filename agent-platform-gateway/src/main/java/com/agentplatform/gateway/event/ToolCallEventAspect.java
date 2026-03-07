package com.agentplatform.gateway.event;

import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 工具调用事件捕获切面
 * 在 ToolDispatcher.dispatch 执行时自动捕获并发布事件
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolCallEventAspect {

    private final EventPublisher eventPublisher;
    private final Tracer tracer;

    /**
     * ThreadLocal 存储当前 runId，支持嵌套调用
     */
    private static final ThreadLocal<String> CURRENT_RUN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_PARENT_STEP_ID = new ThreadLocal<>();

    /**
     * 设置当前 runId（由上层调用者设置）
     */
    public static void setRunId(String runId) {
        CURRENT_RUN_ID.set(runId);
    }

    /**
     * 获取当前 runId
     */
    public static String getRunId() {
        String runId = CURRENT_RUN_ID.get();
        if (runId == null) {
            runId = UUID.randomUUID().toString();
            CURRENT_RUN_ID.set(runId);
        }
        return runId;
    }

    /**
     * 清除当前 runId
     */
    public static void clearRunId() {
        CURRENT_RUN_ID.remove();
        CURRENT_PARENT_STEP_ID.remove();
    }

    @Around("execution(* com.agentplatform.gateway.mcp.router.ToolDispatcher.dispatch(..))")
    public Object captureToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        String stepId = UUID.randomUUID().toString();
        String runId = getRunId();
        String parentStepId = CURRENT_PARENT_STEP_ID.get();

        // 提取参数
        Object[] args = joinPoint.getArgs();
        CallerIdentity identity = (CallerIdentity) args[0];
        String toolName = (String) args[1];
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) args[2];

        // 设置当前步骤为父步骤（支持嵌套调用）
        CURRENT_PARENT_STEP_ID.set(stepId);

        try {
            Object result = joinPoint.proceed();
            long latencyMs = System.currentTimeMillis() - startTime;

            // 发布成功事件
            ToolCallEvent event = ToolCallEvent.builder()
                .runId(runId)
                .stepId(stepId)
                .parentStepId(parentStepId)
                .tenantId(identity.getTenantId())
                .callerId(identity.getClientId())
                .toolName(toolName)
                .arguments(arguments)
                .result(result)
                .latencyMs(latencyMs)
                .status(ToolCallEvent.EventStatus.SUCCESS)
                .traceId(currentTraceId())
                .spanId(currentSpanId())
                .build();

            eventPublisher.publish(event);

            return result;

        } catch (McpException e) {
            long latencyMs = System.currentTimeMillis() - startTime;

            // 发布错误事件
            ToolCallEvent event = ToolCallEvent.builder()
                .runId(runId)
                .stepId(stepId)
                .parentStepId(parentStepId)
                .tenantId(identity.getTenantId())
                .callerId(identity.getClientId())
                .toolName(toolName)
                .arguments(arguments)
                .errorCode(e.getErrorCode().getCode())
                .errorMessage(e.getMessage())
                .latencyMs(latencyMs)
                .status(ToolCallEvent.EventStatus.ERROR)
                .traceId(currentTraceId())
                .spanId(currentSpanId())
                .build();

            eventPublisher.publish(event);

            throw e;

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;

            // 发布错误事件
            ToolCallEvent event = ToolCallEvent.builder()
                .runId(runId)
                .stepId(stepId)
                .parentStepId(parentStepId)
                .tenantId(identity.getTenantId())
                .callerId(identity.getClientId())
                .toolName(toolName)
                .arguments(arguments)
                .errorCode("INTERNAL_ERROR")
                .errorMessage(e.getMessage())
                .latencyMs(latencyMs)
                .status(ToolCallEvent.EventStatus.ERROR)
                .traceId(currentTraceId())
                .spanId(currentSpanId())
                .build();

            eventPublisher.publish(event);

            throw e;

        } finally {
            // 恢复父步骤 ID
            CURRENT_PARENT_STEP_ID.set(parentStepId);
        }
    }

    private String currentTraceId() {
        if (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
            return tracer.currentSpan().context().traceId();
        }
        return null;
    }

    private String currentSpanId() {
        if (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
            return tracer.currentSpan().context().spanId();
        }
        return null;
    }
}
