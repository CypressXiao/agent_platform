package com.agentplatform.gateway.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 统一事件发布器
 * 发布 ToolCallEvent 到 Spring 事件总线，支持异步处理和多订阅者
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 发布工具调用事件
     */
    public void publish(ToolCallEvent event) {
        log.debug("Publishing ToolCallEvent: runId={}, stepId={}, tool={}, status={}",
            event.getRunId(), event.getStepId(), event.getToolName(), event.getStatus());
        
        applicationEventPublisher.publishEvent(event);
    }
}
