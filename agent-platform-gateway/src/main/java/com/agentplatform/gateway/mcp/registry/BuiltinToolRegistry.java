package com.agentplatform.gateway.mcp.registry;

import com.agentplatform.common.model.Tool;
import com.agentplatform.common.repository.ToolRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for built-in tool handlers.
 * Auto-discovers all BuiltinToolHandler beans and registers them as system tools.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BuiltinToolRegistry {

    private final List<BuiltinToolHandler> handlers;
    private final ToolRepository toolRepo;
    private final Map<String, BuiltinToolHandler> handlerMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (BuiltinToolHandler handler : handlers) {
            handlerMap.put(handler.toolName(), handler);
            ensureToolRecord(handler);
            log.info("Registered built-in tool: {}", handler.toolName());
        }
    }

    public Optional<BuiltinToolHandler> getHandler(String toolName) {
        return Optional.ofNullable(handlerMap.get(toolName));
    }

    public Collection<BuiltinToolHandler> getAllHandlers() {
        return handlerMap.values();
    }

    private void ensureToolRecord(BuiltinToolHandler handler) {
        String toolId = "builtin:" + handler.toolName();
        if (toolRepo.findById(toolId).isEmpty()) {
            Tool tool = Tool.builder()
                .toolId(toolId)
                .toolName(handler.toolName())
                .description(handler.description())
                .sourceType("builtin")
                .sourceId("builtin")
                .ownerTid("system")
                .inputSchema(handler.inputSchema())
                .status("active")
                .build();
            toolRepo.save(tool);
        }
    }
}
