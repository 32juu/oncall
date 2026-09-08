package org.example.claim.entity;

/**
 * 告警生命周期状态：已诊断 → 处理中 → 已解决/已关闭。
 * 认领执行 DIAGNOSED → IN_PROGRESS；处置（US3，recordDisposition）执行 IN_PROGRESS → RESOLVED/CLOSED，
 * 由 {@code endIfOwner} 原子条件更新保证（仅当前负责人，参照 data-model §3）。
 */
public enum AlertStatus {
    DIAGNOSED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
}
