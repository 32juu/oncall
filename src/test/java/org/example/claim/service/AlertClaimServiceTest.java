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
}
