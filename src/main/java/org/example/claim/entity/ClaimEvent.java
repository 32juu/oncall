package org.example.claim.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 认领/事件历史（表 claim_events），只追加。V1 写 CLAIM；US2 写 SUPPRESS / SUPPRESS_CANCEL；
 * US3（处置/结局）写 RESOLVE / CLOSE，note 列承载处置动作文案。
 * 同时承载审计与「诊断→认领→…→结局」时间线（复盘数据源）。
 */
@Entity
@Table(name = "claim_events")
@Getter
@Setter
public class ClaimEvent {

    public static final String TYPE_CLAIM = "CLAIM";
    public static final String TYPE_SUPPRESS = "SUPPRESS";
    public static final String TYPE_SUPPRESS_CANCEL = "SUPPRESS_CANCEL";
    public static final String TYPE_RESOLVE = "RESOLVE";
    public static final String TYPE_CLOSE = "CLOSE";

    /** note（处置动作文案）上限：列长与服务端守卫共用一个来源。 */
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_name", nullable = false, length = 128)
    private String alertName;

    @Column(nullable = false, length = 64)
    private String operator;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    /** 处置动作文案（US3）：仅 RESOLVE/CLOSE 事件携带；CLAIM/SUPPRESS 类事件为 null。 */
    @Column(length = ClaimEvent.NOTE_MAX_LENGTH)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
