package com.agentplatform.gateway.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Tracing 服务
 * 封装 Micrometer Tracing，提供简化的 Span 管理
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TracingService {

    private final Tracer tracer;
    private final ObservationRegistry observationRegistry;

    /**
     * 获取当前 TraceId
     */
    public String getCurrentTraceId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan != null ? currentSpan.context().traceId() : null;
    }

    /**
     * 获取当前 SpanId
     */
    public String getCurrentSpanId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan != null ? currentSpan.context().spanId() : null;
    }

    /**
     * 创建新的 Span
     */
    public SpanWrapper startSpan(String name) {
        Span span = tracer.nextSpan().name(name).start();
        return new SpanWrapper(span, tracer);
    }

    /**
     * 创建带标签的 Span
     */
    public SpanWrapper startSpan(String name, Map<String, String> tags) {
        Span span = tracer.nextSpan().name(name);
        tags.forEach(span::tag);
        span.start();
        return new SpanWrapper(span, tracer);
    }

    /**
     * 在 Span 中执行操作
     */
    public <T> T executeInSpan(String name, Supplier<T> operation) {
        try (SpanWrapper span = startSpan(name)) {
            try {
                T result = operation.get();
                span.success();
                return result;
            } catch (Exception e) {
                span.error(e);
                throw e;
            }
        }
    }

    /**
     * 在 Span 中执行操作（无返回值）
     */
    public void executeInSpan(String name, Runnable operation) {
        try (SpanWrapper span = startSpan(name)) {
            try {
                operation.run();
                span.success();
            } catch (Exception e) {
                span.error(e);
                throw e;
            }
        }
    }

    /**
     * 创建 Observation（更高级的追踪）
     */
    public Observation startObservation(String name) {
        return Observation.start(name, observationRegistry);
    }

    /**
     * Span 包装器，支持 try-with-resources
     */
    public static class SpanWrapper implements AutoCloseable {
        private final Span span;

        public SpanWrapper(Span span, Tracer tracer) {
            this.span = span;
        }

        public SpanWrapper tag(String key, String value) {
            span.tag(key, value);
            return this;
        }

        public SpanWrapper event(String name) {
            span.event(name);
            return this;
        }

        public void success() {
            span.tag("status", "success");
        }

        public void error(Throwable e) {
            span.tag("status", "error");
            span.tag("error.message", e.getMessage());
            span.error(e);
        }

        public String getTraceId() {
            return span.context().traceId();
        }

        public String getSpanId() {
            return span.context().spanId();
        }

        @Override
        public void close() {
            span.end();
        }
    }
}
