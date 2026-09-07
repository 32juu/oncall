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

    /**
     * 设置抑制窗口（US2）：仅当前负责人对「处理中」告警可设置；until 必须晚于当前；覆盖式替换（单活动窗口）。
     */
    @Transactional
    public AlertView suppress(String alertName, String operator, Instant until) {
        requireAlertName(alertName);
        requireOperator(operator);
        requireUntilInFuture(until);
        return updateSuppression(alertName, operator, until, ClaimEvent.TYPE_SUPPRESS);
    }

    /**
     * 取消抑制窗口（US2）：仅当前负责人可取消；本无窗口视为成功（幂等、不记事件，避免脏审计）。
     */
    @Transactional
    public AlertView cancelSuppression(String alertName, String operator) {
        requireAlertName(alertName);
        requireOperator(operator);
        return updateSuppression(alertName, operator, null, ClaimEvent.TYPE_SUPPRESS_CANCEL);
    }

    /**
     * 抑制写路径（设置/取消共用）：CAS 更新仅命中「处理中且本人负责且值确有变更」的行。
     * affected==1 → 记事件并回读视图；affected==0 → 回读分类：
     * 本人且处理中 = 幂等成功（无实际变更：取消无窗口 / 重复设置同值，不记事件）；
     * 非本人 / 未认领 / 已结束 / 不存在 → 守卫异常。
     */
    private AlertView updateSuppression(String alertName, String operator, Instant until, String eventType) {
        int affected = alertRepository.setSuppression(alertName, operator, until, Instant.now());
        if (affected == 1) {
            appendEvent(alertName, operator, eventType);
            return AlertView.from(requireAlert(alertName));
        }
        Alert current = requireAlert(alertName);
        switch (current.getStatus()) {
            case IN_PROGRESS -> {
                if (Objects.equals(operator, current.getClaimedBy())) {
                    return AlertView.from(current); // 幂等成功：值未变
                }
                throw new AlertClaimException(ErrorCode.SUPPRESS_NOT_OWNER);
            }
            case RESOLVED, CLOSED -> throw new AlertClaimException(ErrorCode.ALERT_ENDED,
                    "该告警已结束，无法设置或取消抑制窗口");
            default -> throw new AlertClaimException(ErrorCode.ALERT_NOT_CLAIMED); // DIAGNOSED：先认领
        }
    }

    private void appendEvent(String alertName, String operator, String eventType) {
        ClaimEvent event = new ClaimEvent();
        event.setAlertName(alertName);
        event.setOperator(operator);
        event.setEventType(eventType);
        claimEventRepository.save(event);
    }

    private void requireUntilInFuture(Instant until) {
        if (until == null || !until.isAfter(Instant.now())) {
            throw new AlertClaimException(ErrorCode.INVALID_UNTIL);
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
