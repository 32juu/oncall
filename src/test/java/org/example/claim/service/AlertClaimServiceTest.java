package org.example.claim.service;

import org.example.claim.dto.AlertClaimException;
import org.example.claim.dto.AlertView;
import org.example.claim.dto.ErrorCode;
import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.example.claim.entity.ClaimEvent;
import org.example.claim.repository.AlertRepository;
import org.example.claim.repository.ClaimEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认领服务守卫分支映射（40401 / 40901 / 40902 / 40903 / 400），并发正确性由 AlertRepository 条件更新承载，
 * 此处用 Mockito 验证编排与映射逻辑。
 */
@ExtendWith(MockitoExtension.class)
class AlertClaimServiceTest {

    private static final String ALERT = "HighCPUUsage";

    @Mock
    private AlertRepository alertRepository;
    @Mock
    private ClaimEventRepository claimEventRepository;

    @InjectMocks
    private AlertClaimService service;

    private Alert alert(String status, String claimedBy) {
        Alert a = new Alert();
        a.setAlertName(ALERT);
        a.setStatus(AlertStatus.valueOf(status));
        a.setClaimedBy(claimedBy);
        a.setClaimedAt(claimedBy == null ? null : Instant.now());
        a.setLastDiagnosedAt(Instant.now());
        return a;
    }

    @Test
    void claim_success_returnsViewAndWritesClaimEvent() {
        when(alertRepository.claimIfDiagnosed(any(String.class), any(String.class), any(Instant.class))).thenReturn(1);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("IN_PROGRESS", "sre-alice")));

        AlertView view = service.claim(ALERT, "sre-alice");

        assertThat(view.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
        assertThat(view.getClaimedBy()).isEqualTo("sre-alice");
        verify(claimEventRepository).save(argThat(e ->
                ALERT.equals(e.getAlertName())
                        && "sre-alice".equals(e.getOperator())
                        && ClaimEvent.TYPE_CLAIM.equals(e.getEventType())));
    }

    @Test
    void claim_rejectedByOther_throwsAlreadyClaimedWithOwner() {
        when(alertRepository.claimIfDiagnosed(any(String.class), any(String.class), any(Instant.class))).thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("IN_PROGRESS", "sre-bob")));

        assertThatThrownBy(() -> service.claim(ALERT, "sre-alice"))
                .isInstanceOfSatisfying(AlertClaimException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_ALREADY_CLAIMED);
                    assertThat(ex.getMessage()).contains("sre-bob");
                });
        verify(claimEventRepository, never()).save(any(ClaimEvent.class));
    }

    @Test
    void claim_selfReclaim_throwsSelfClaimed() {
        when(alertRepository.claimIfDiagnosed(any(String.class), any(String.class), any(Instant.class))).thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("IN_PROGRESS", "sre-alice")));

        assertThatThrownBy(() -> service.claim(ALERT, "sre-alice"))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_SELF_CLAIMED));
    }

    @Test
    void claim_endedAlert_throwsEnded() {
        when(alertRepository.claimIfDiagnosed(any(String.class), any(String.class), any(Instant.class))).thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("RESOLVED", "sre-bob")));

        assertThatThrownBy(() -> service.claim(ALERT, "sre-alice"))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_ENDED));
    }

    @Test
    void claim_unknownAlert_throwsNotFound() {
        when(alertRepository.claimIfDiagnosed(any(String.class), any(String.class), any(Instant.class))).thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(ALERT, "sre-alice"))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_NOT_FOUND));
    }

    @Test
    void claim_blankOperator_throws400() {
        assertThatThrownBy(() -> service.claim(ALERT, "  "))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BLANK_OPERATOR));
    }

    @Test
    void claim_blankAlert_throws400() {
        assertThatThrownBy(() -> service.claim("", "sre-alice"))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BLANK_ALERT));
    }

    @Test
    void list_filtersByStatus_whenProvided() {
        Alert diag = alert("DIAGNOSED", null);
        when(alertRepository.findByStatus(AlertStatus.DIAGNOSED)).thenReturn(List.of(diag));

        List<AlertView> views = service.list(AlertStatus.DIAGNOSED);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getAlertName()).isEqualTo(ALERT);
    }

    @Test
    void events_returnTimeline_ordered() {
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("IN_PROGRESS", "sre-alice")));
        ClaimEvent e = new ClaimEvent();
        e.setId(1L);
        e.setAlertName(ALERT);
        e.setOperator("sre-alice");
        e.setEventType(ClaimEvent.TYPE_CLAIM);
        when(claimEventRepository.findByAlertNameOrderByCreatedAtAsc(ALERT)).thenReturn(List.of(e));

        var views = service.events(ALERT);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getEventType()).isEqualTo(ClaimEvent.TYPE_CLAIM);
    }

    // ============ US2 抑制窗口 ============
    // ⚠️ 用远未来/远过去固定时刻作基线：相对"现在"过期的时刻（如 2026-09-07 13:00Z）会随墙上时钟变陈旧，
    //    使 requireUntilInFuture 意外抛 40004（本文件首版 5 用例即因此红）。

    private static final Instant UNTIL = Instant.parse("2099-01-01T00:00:00.123456Z");

    private Alert suppressedAlert(String status, String claimedBy, Instant suppressedUntil) {
        Alert a = alert(status, claimedBy);
        a.setSuppressedUntil(suppressedUntil);
        return a;
    }

    @Test
    void suppress_success_returnsViewAndWritesSuppressEvent() {
        when(alertRepository.setSuppression(eq(ALERT), eq("sre-alice"), nullable(Instant.class), any(Instant.class)))
                .thenReturn(1);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(suppressedAlert("IN_PROGRESS", "sre-alice", UNTIL)));

        AlertView view = service.suppress(ALERT, "sre-alice", UNTIL);

        assertThat(view.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
        assertThat(view.getSuppressedUntil()).isEqualTo(UNTIL);
        verify(claimEventRepository).save(argThat(e ->
                ALERT.equals(e.getAlertName())
                        && "sre-alice".equals(e.getOperator())
                        && ClaimEvent.TYPE_SUPPRESS.equals(e.getEventType())));
    }

    @Test
    void suppress_invalidUntilPast_throwsInvalidUntil() {
        assertThatThrownBy(() -> service.suppress(ALERT, "sre-alice", Instant.parse("2000-01-01T00:00:00Z")))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_UNTIL));
        verify(alertRepository, never()).setSuppression(any(), any(), any(), any());
    }

    @Test
    void suppress_blankOperator_throwsBlankOperator() {
        assertThatThrownBy(() -> service.suppress(ALERT, "  ", UNTIL))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BLANK_OPERATOR));
    }

    @Test
    void suppress_notOwner_throwsSuppressNotOwner() {
        when(alertRepository.setSuppression(any(String.class), any(String.class), nullable(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("IN_PROGRESS", "sre-bob")));

        assertThatThrownBy(() -> service.suppress(ALERT, "sre-alice", UNTIL))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SUPPRESS_NOT_OWNER));
        verify(claimEventRepository, never()).save(any(ClaimEvent.class));
    }

    @Test
    void suppress_notClaimedYet_throwsNotClaimed() {
        when(alertRepository.setSuppression(any(String.class), any(String.class), nullable(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("DIAGNOSED", null)));

        assertThatThrownBy(() -> service.suppress(ALERT, "sre-alice", UNTIL))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_NOT_CLAIMED));
    }

    @Test
    void suppress_endedAlert_throwsEnded() {
        when(alertRepository.setSuppression(any(String.class), any(String.class), nullable(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("RESOLVED", "sre-bob")));

        assertThatThrownBy(() -> service.suppress(ALERT, "sre-alice", UNTIL))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_ENDED));
    }

    @Test
    void suppress_unknownAlert_throwsNotFound() {
        when(alertRepository.setSuppression(any(String.class), any(String.class), nullable(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suppress(ALERT, "sre-alice", UNTIL))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALERT_NOT_FOUND));
    }

    @Test
    void cancel_success_removesWindow_returnsViewAndWritesCancelEvent() {
        when(alertRepository.setSuppression(eq(ALERT), eq("sre-alice"), nullable(Instant.class), any(Instant.class)))
                .thenReturn(1);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(suppressedAlert("IN_PROGRESS", "sre-alice", null)));

        AlertView view = service.cancelSuppression(ALERT, "sre-alice");

        assertThat(view.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
        assertThat(view.getSuppressedUntil()).isNull();
        verify(claimEventRepository).save(argThat(e -> ClaimEvent.TYPE_SUPPRESS_CANCEL.equals(e.getEventType())));
    }

    @Test
    void cancel_idempotent_noActiveWindow_succeedsWithoutEvent() {
        // setSuppression 返回 0（本无窗口，值无变更）：幂等成功、不记事件
        when(alertRepository.setSuppression(eq(ALERT), eq("sre-alice"), nullable(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(suppressedAlert("IN_PROGRESS", "sre-alice", null)));

        AlertView view = service.cancelSuppression(ALERT, "sre-alice");

        assertThat(view.getSuppressedUntil()).isNull();
        verify(claimEventRepository, never()).save(any(ClaimEvent.class));
    }

    @Test
    void cancel_notOwner_throwsSuppressNotOwner() {
        when(alertRepository.setSuppression(any(String.class), any(String.class), nullable(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(alertRepository.findById(ALERT)).thenReturn(Optional.of(alert("IN_PROGRESS", "sre-bob")));

        assertThatThrownBy(() -> service.cancelSuppression(ALERT, "sre-alice"))
                .isInstanceOfSatisfying(AlertClaimException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SUPPRESS_NOT_OWNER));
    }
}
