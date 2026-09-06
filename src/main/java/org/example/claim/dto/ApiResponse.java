package org.example.claim.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 共享响应信封，沿用项目既有 {code, message, data} 形状。
 * 与遗留 ChatController 内嵌 ApiResponse 的差异：本模块按真实 HTTP 状态码返回（见 plan O1）。
 */
@Getter
@Setter
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        ErrorCode ok = ErrorCode.SUCCESS;
        return new ApiResponse<>(ok.getCode(), ok.getMessage(), data);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMessage());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String overrideMessage) {
        return new ApiResponse<>(errorCode.getCode(), overrideMessage, null);
    }
}
