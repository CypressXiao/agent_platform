package com.agentplatform.common.exception;

import lombok.Getter;

@Getter
public class McpException extends RuntimeException {

    private final McpErrorCode errorCode;
    private final String detail;

    public McpException(McpErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.detail = errorCode.getDefaultMessage();
    }

    public McpException(McpErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public McpException(McpErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public int getHttpStatus() {
        return errorCode.getHttpStatus();
    }
}
