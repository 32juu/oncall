package org.example.claim.dto;

import lombok.Getter;

/**
 * 认领模块业务异常：携带 ErrorCode，由 controller 统一映射为 HTTP 状态码与信封。
 * message 可覆盖（如 40901 需拼入当前负责人标识）。
 */
@Getter
public class AlertClaimException extends RuntimeException {

    private final ErrorCode errorCode;

    public AlertClaimException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AlertClaimException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
