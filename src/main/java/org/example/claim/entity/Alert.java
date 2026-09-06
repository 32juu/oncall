package org.example.claim.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 告警（表 alerts）。一行 = 一条告警，行内字段承载当前状态与归属（V1 认领的排他不变量在行上）。
 * 稳定标识 = alert_name（当前 Prometheus 数据源已按 alertname 去重，见 plan D3/R3）。
 */
@Entity
@Table(name = "alerts")
@Getter
@Setter
public class Alert {

    @Id
    @Column(name = "alert_name", length = 128)
    private String alertName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status = AlertStatus.DIAGNOSED;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_diagnosed_at", nullable = false)
    private Instant lastDiagnosedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
