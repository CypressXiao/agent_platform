package com.agentplatform.gateway.prompt;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.prompt.model.PromptTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板 API（租户级别）
 */
@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
@Tag(name = "Prompt", description = "Prompt 模板管理")
@ConditionalOnProperty(name = "agent-platform.prompt.enabled", havingValue = "true")
public class PromptAdminController {

    private final PromptService promptService;

    @Operation(summary = "列出模板", description = "获取当前租户的所有 Prompt 模板")
    @GetMapping
    public ResponseEntity<List<PromptTemplate>> list(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity) {
        return ResponseEntity.ok(promptService.list(identity));
    }

    @Operation(summary = "创建模板", description = "创建新的 Prompt 模板")
    @PostMapping
    public ResponseEntity<PromptTemplate> create(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @RequestBody CreatePromptRequest request) {
        PromptTemplate template = promptService.create(
            identity,
            request.getName(),
            request.getDescription(),
            request.getTemplate(),
            request.getVariables()
        );
        return ResponseEntity.ok(template);
    }

    @Operation(summary = "获取模板", description = "根据名称获取 Prompt 模板，可指定版本")
    @GetMapping("/{name}")
    public ResponseEntity<PromptTemplate> get(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @Parameter(description = "模板名称") @PathVariable String name,
            @Parameter(description = "版本号，不指定则返回最新版本") @RequestParam(required = false) Integer version) {
        String tenantId = identity.getTenantId();
        
        if (version != null) {
            return promptService.getByVersion(tenantId, name, version)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        }
        
        return promptService.getLatest(tenantId, name)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "创建新版本", description = "为现有模板创建新版本")
    @PostMapping("/{name}/versions")
    public ResponseEntity<PromptTemplate> createVersion(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @Parameter(description = "模板名称") @PathVariable String name,
            @RequestBody CreateVersionRequest request) {
        PromptTemplate template = promptService.createNewVersion(
            identity,
            name,
            request.getTemplate(),
            request.getDescription()
        );
        return ResponseEntity.ok(template);
    }

    @Operation(summary = "渲染模板", description = "使用变量渲染 Prompt 模板")
    @PostMapping("/{name}/render")
    public ResponseEntity<Map<String, Object>> render(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @Parameter(description = "模板名称") @PathVariable String name,
            @RequestBody Map<String, Object> variables) {
        String tenantId = identity.getTenantId();
        String rendered = promptService.render(tenantId, name, variables);
        return ResponseEntity.ok(Map.of(
            "template_name", name,
            "rendered", rendered
        ));
    }

    @Data
    @Schema(description = "创建模板请求")
    public static class CreatePromptRequest {
        @Schema(description = "模板名称", example = "chat_prompt")
        private String name;
        @Schema(description = "模板描述", example = "通用对话 Prompt")
        private String description;
        @Schema(description = "模板内容", example = "你是 {{role}}，请回答用户的问题：{{question}}")
        private String template;
        @Schema(description = "变量列表", example = "[\"role\", \"question\"]")
        private List<String> variables;
    }

    @Data
    @Schema(description = "创建新版本请求")
    public static class CreateVersionRequest {
        @Schema(description = "新版本模板内容")
        private String template;
        @Schema(description = "版本描述")
        private String description;
    }
}
