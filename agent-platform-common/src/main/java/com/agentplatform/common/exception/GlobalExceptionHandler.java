package com.agentplatform.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(McpException.class)
    public ResponseEntity<Map<String, Object>> handleMcpException(McpException ex) {
        log.warn("MCP error: code={}, detail={}", ex.getErrorCode().getCode(), ex.getDetail());

        Map<String, Object> body = Map.of(
            "error", Map.of(
                "code", ex.getErrorCode().getCode(),
                "message", ex.getDetail(),
                "timestamp", Instant.now().toString()
            )
        );

        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> body = Map.of(
            "error", Map.of(
                "code", "BAD_REQUEST",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
            )
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        Map<String, Object> body = Map.of(
            "error", Map.of(
                "code", "INTERNAL_ERROR",
                "message", "Internal server error",
                "timestamp", Instant.now().toString()
            )
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
