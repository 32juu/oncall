package org.example.claim.service;

import org.example.claim.dto.AlertClaimException;
import org.example.claim.dto.AlertView;
import org.example.claim.dto.ClaimEventView;
import org.example.claim.dto.ErrorCode;
import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.example.claim.entity.ClaimEvent;
import org.example.claim.repository.AlertRepository;
import org.example.claim.repository.ClaimEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 认领编排。并发安全不放在应用层：claim 内部先以条件 UPDATE（受影响行数判成败）写状态，
 * 失败再回读分类抛出 404/409 守卫异常。参见 data-model §4 / plan D2。
 */
@Service
public class AlertClaimService {

    private final AlertRepository alertRepository;
    private final ClaimEventRepository claimEventRepository;

    public AlertClaimService(AlertRepository alertRepository, ClaimEventRepository claimEventRepository) {
        this.alertRepository = alertRepository;
        this.claimEventRepository = claimEventRepository;
    }

    @Transactional(readOnly = true)
    public AlertView get(String alertName) {
        requireAlertName(alertName);
        return AlertView.from(requireAlert(alertName));
    }

    @Transactional(readOnly = true)
    public List<AlertView> list(AlertStatus status) {
        List<Alert> alerts = (status == null)
                ? alertRepository.findAll()
                : alertRepository.findByStatus(status);
        return alerts.stream().map(AlertView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimEventView> events(String alertName) {
        requireAlertName(alertName);
        requireAlert(alertName);
        return claimEventRepository.findByAlertNameOrderByCreatedAtAsc(alertName)
                .stream().map(ClaimEventView::from).toList();
    }

    /**
     * 认领：先到先得、无改派（锁定到结束）。唯一写路径。
     */
    @Transactional
    public AlertView claim(String alertName, String operator) {
        requireAlertName(alertName);
        requireOperator(operator);

        int affected = alertRepository.claimIfDiagnosed(alertName, operator, Instant.now());

        if (affected == 1) {
            ClaimEvent event = new ClaimEvent();
            event.setAlertName(alertName);
            event.setOperator(operator);
            event.setEventType(ClaimEvent.TYPE_CLAIM);
            claimEventRepository.save(event);
            return AlertView.from(requireAlert(alertName));
        }

        // 认领失败：回读当前行，分类守卫分支
        Alert current = requireAlert(alertName);
        switch (current.getStatus()) {
            case IN_PROGRESS -> {
                if (Objects.equals(operator, current.getClaimedBy())) {
                    throw new AlertClaimException(ErrorCode.ALERT_SELF_CLAIMED);
                }
                throw new AlertClaimException(ErrorCode.ALERT_ALREADY_CLAIMED, ownerMessage(current.getClaimedBy()));
            }
            case RESOLVED, CLOSED -> throw new AlertClaimException(ErrorCode.ALERT_ENDED);
            default -> throw new AlertClaimException(ErrorCode.ALERT_ALREADY_CLAIMED, ownerMessage(current.getClaimedBy()));
        }
    }

    private Alert requireAlert(String alertName) {
        return alertRepository.findById(alertName)
                .orElseThrow(() -> new AlertClaimException(ErrorCode.ALERT_NOT_FOUND));
    }

    private String ownerMessage(String claimedBy) {
        return (claimedBy == null || claimedBy.isBlank())
                ? ErrorCode.ALERT_ALREADY_CLAIMED.getMessage()
                : "该告警已被 " + claimedBy + " 负责，无法重复认领";
    }

    private void requireAlertName(String alertName) {
        if (alertName == null || alertName.isBlank()) {
            throw new AlertClaimException(ErrorCode.BLANK_ALERT);
        }
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new AlertClaimException(ErrorCode.BLANK_OPERATOR);
        }
    }
}
