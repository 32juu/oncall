package org.example.claim.dto;

import lombok.Data;

import java.time.Instant;

/**
 * 抑制请求体：POST /api/alerts/{alertName}/suppress { "operator": "sre-alice", "until": "2026-09-07T12:00:00Z" }。
 * until = 窗口结束时刻（ISO Instant），必须晚于当前（US2 FR-009 / 验收场景 AC5）。
 * 取消窗口走 DELETE /api/alerts/{alertName}/suppress?operator=...，无 body。
 */
@Data
public class SuppressRequest {

    private String operator;

    private Instant until;
}
