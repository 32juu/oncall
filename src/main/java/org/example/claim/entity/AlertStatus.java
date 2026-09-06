package org.example.claim.entity;

/**
 * 告警生命周期状态：已诊断 → 处理中 → 已解决/已关闭。
 * V1（认领闭环）仅允许执行 {@link #DIAGNOSED} → {@link #IN_PROGRESS} 迁移；
 * {@link #RESOLVED} / {@link #CLOSED} 为 P3 处置记录预留。
 */
public enum AlertStatus {
    DIAGNOSED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
}
