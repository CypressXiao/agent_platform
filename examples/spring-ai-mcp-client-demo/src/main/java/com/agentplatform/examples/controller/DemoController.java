package com.agentplatform.examples.controller;

import com.agentplatform.examples.service.McpGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Demo 控制器。
 * 
 * 提供 REST API 来演示 MCP 调用。
 * 启动后可以通过 curl 或浏览器测试。
 */
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoController {

    private final McpGatewayClient mcpClient;

    /**
     * 初始化 MCP 连接。
     * 
     * curl http://localhost:8081/demo/initialize
     */
    @GetMapping("/initialize")
    public Mono<Map<String, Object>> initialize() {
        return mcpClient.initialize();
    }

    /**
     * 获取工具列表。
     * 
     * curl http://localhost:8081/demo/tools
     */
    @GetMapping("/tools")
    public Mono<Map<String, Object>> listTools() {
        return mcpClient.listTools();
    }

    /**
     * 调用指定工具。
     * 
     * curl -X POST http://localhost:8081/demo/tools/call \
     *   -H "Content-Type: application/json" \
     *   -d '{"name": "weather", "arguments": {"city": "Beijing"}}'
     */
    @PostMapping("/tools/call")
    public Mono<Map<String, Object>> callTool(@RequestBody ToolCallRequest request) {
        return mcpClient.callTool(request.name(), request.arguments());
    }

    public record ToolCallRequest(String name, Map<String, Object> arguments) {}
}
