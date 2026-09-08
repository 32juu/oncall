package org.example.claim.dto;

import lombok.Getter;

/**
 * 认领模块错误码与默认文案。code 同时写入响应信封；HTTP 状态由 controller 依类别映射
 * （4xxxx → 4xx，见 contracts/api.md）。
 */
@Getter
public enum ErrorCode {

    SUCCESS(200, "ok"),
    BLANK_OPERATOR(40001, "操作人标识不能为空"),
    BLANK_ALERT(40002, "告警标识不能为空"),
    INVALID_STATUS_FILTER(40003, "非法状态过滤值"),
    INVALID_UNTIL(40004, "抑制结束时刻必须晚于当前时间"),
    INVALID_OUTCOME(40006, "处置结局必须是 RESOLVED（已解决）或 CLOSED（误报/关闭）"),
    ACTION_TOO_LONG(40007, "处置动作描述不能超过 500 字"),
    ALERT_NOT_FOUND(40401, "告警不存在或尚未被诊断，无法认领"),
    ALERT_ALREADY_CLAIMED(40901, "该告警已被其他负责人接管，无法重复认领"),
    ALERT_SELF_CLAIMED(40902, "该告警已由您负责，无需重复认领"),
    ALERT_ENDED(40903, "该告警已结束，无法认领"),
    ALERT_NOT_CLAIMED(40904, "仅处理中（已认领）的告警可设置抑制窗口"),
    SUPPRESS_NOT_OWNER(40905, "仅当前负责人可设置或取消抑制窗口"),
    DISPOSITION_NOT_OWNER(40906, "仅当前负责人可记录处置动作与结局"),
    INTERNAL_ERROR(50000, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
