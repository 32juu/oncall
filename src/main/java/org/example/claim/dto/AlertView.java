package org.example.claim.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;

import java.time.Instant;

/**
 * 告警对外视图（读路径：单条 / 列表）。不含内部审计字段。
 */
@Getter
@Setter
public class AlertView {

    private String alertName;
    private AlertStatus status;
    private String claimedBy;
    private Instant claimedAt;
    private Instant lastDiagnosedAt;
    private Instant suppressedUntil;

    public AlertView() {
    }

    public AlertView(String alertName, AlertStatus status, String claimedBy,
                     Instant claimedAt, Instant lastDiagnosedAt) {
        this.alertName = alertName;
        this.status = status;
        this.claimedBy = claimedBy;
        this.claimedAt = claimedAt;
        this.lastDiagnosedAt = lastDiagnosedAt;
    }

    public static AlertView from(Alert alert) {
        AlertView view = new AlertView(
                alert.getAlertName(),
                alert.getStatus(),
                alert.getClaimedBy(),
                alert.getClaimedAt(),
                alert.getLastDiagnosedAt());
        view.setSuppressedUntil(alert.getSuppressedUntil());
        return view;
    }
}
