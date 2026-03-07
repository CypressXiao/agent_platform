package com.agentplatform.gateway.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内容安全 API
 */
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
@Tag(name = "Content Security", description = "内容安全检测与脱敏")
public class ContentSecurityController {

    private final ContentSecurityService securityService;

    @PostMapping("/filter/input")
    @Operation(summary = "检测输入内容", description = "检测输入内容是否包含 PII、越狱尝试、有害内容")
    public ResponseEntity<ContentFilter> filterInput(@RequestBody FilterRequest request) {
        return ResponseEntity.ok(securityService.filterInput(request.getContent()));
    }

    @PostMapping("/filter/output")
    @Operation(summary = "检测输出内容", description = "检测输出内容是否包含 PII 泄露、有害内容")
    public ResponseEntity<ContentFilter> filterOutput(@RequestBody FilterRequest request) {
        return ResponseEntity.ok(securityService.filterOutput(request.getContent()));
    }

    @PostMapping("/pii/detect")
    @Operation(summary = "检测 PII", description = "检测内容中的个人身份信息")
    public ResponseEntity<Map<String, Object>> detectPII(@RequestBody FilterRequest request) {
        List<String> piiTypes = securityService.detectPII(request.getContent());
        return ResponseEntity.ok(Map.of(
            "hasPII", !piiTypes.isEmpty(),
            "piiTypes", piiTypes
        ));
    }

    @PostMapping("/pii/sanitize")
    @Operation(summary = "脱敏 PII", description = "对内容中的个人身份信息进行脱敏处理")
    public ResponseEntity<Map<String, String>> sanitizePII(@RequestBody FilterRequest request) {
        String sanitized = securityService.sanitizePII(request.getContent());
        return ResponseEntity.ok(Map.of(
            "original", request.getContent(),
            "sanitized", sanitized
        ));
    }

    @PostMapping("/filter/batch")
    @Operation(summary = "批量检测", description = "批量检测多个内容")
    public ResponseEntity<Map<String, ContentFilter>> batchFilter(
            @RequestBody BatchFilterRequest request) {
        return ResponseEntity.ok(securityService.batchFilter(request.getContents()));
    }

    @lombok.Data
    public static class FilterRequest {
        private String content;
    }

    @lombok.Data
    public static class BatchFilterRequest {
        private List<String> contents;
    }
}
