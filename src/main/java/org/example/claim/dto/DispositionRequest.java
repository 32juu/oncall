package org.example.claim.dto;

import lombok.Data;

/**
 * 处置请求体：POST /api/alerts/{alertName}/disposition { "operator": "sre-alice", "outcome": "RESOLVED", "action": "..." }。
 * outcome = 终结结局，取 RESOLVED（已解决）或 CLOSED（误报/关闭）；一次调用同时记录动作并让告警进入对应终态（US3）。
 * 用 String 收 outcome 而非枚举：Jackson 枚举反序列化失败会落成 500（HttpMessageNotReadable → 兜底处理器），
 * 收 String 让 service 统一解析转 40006（守卫集中在 service，同 BLANK 校验模式）。
 * action = 处置动作描述，可选、≤500 字。
 */
@Data
public class DispositionRequest {

    private String operator;

    private String outcome;

    private String action;
}
