package org.example.claim.repository;

import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 处置终局 CAS（endIfOwner）：仅「处理中且本人负责」的告警可迁移到终态（RESOLVED/CLOSED），并顺手清除抑制窗口。
 * 镜像 AlertRepository.claimIfDiagnosed / setSuppression 的并发语义：行锁 + 谓词重估，0 行 = 守卫分支。
 *
 * ⚠️ 断言用固定 ISO 时刻（微秒对齐）：H2 TIMESTAMP 存储精度为微秒，派生 Instant.now() 会被截断致不稳定测试。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AlertDispositionRepositoryTest {

    @Autowired
    private AlertRepository alertRepository;

    private static final String ALERT = "HighCPUUsage";
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private void seed(String alertName, AlertStatus status, String claimedBy, Instant suppressedUntil) {
        Alert alert = new Alert();
        alert.setAlertName(alertName);
        alert.setStatus(status);
        alert.setClaimedBy(claimedBy);
        alert.setSuppressedUntil(suppressedUntil);
        alert.setLastDiagnosedAt(NOW);
        alertRepository.save(alert);
    }

    @BeforeEach
    void cleanUp() {
        alertRepository.deleteAll();
    }

    @Test
    void endIfOwner_returnsOne_resolvesOwnedInProgress_andClearsSuppression() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-alice", Instant.parse("2026-09-07T13:00:00Z"));

        int affected = alertRepository.endIfOwner(ALERT, "sre-alice", "RESOLVED", NOW);

        assertThat(affected).isEqualTo(1);
        Alert reloaded = alertRepository.findById(ALERT).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(reloaded.getSuppressedUntil()).isNull(); // 结局 = 抑制无意义，一并清掉
    }

    @Test
    void endIfOwner_returnsOne_closesToClosedStatus() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-alice", null);

        int affected = alertRepository.endIfOwner(ALERT, "sre-alice", "CLOSED", NOW);

        assertThat(affected).isEqualTo(1);
        assertThat(alertRepository.findById(ALERT).orElseThrow().getStatus()).isEqualTo(AlertStatus.CLOSED);
    }

    @Test
    void endIfOwner_returnsZero_whenNotOwner() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-bob", null);

        int affected = alertRepository.endIfOwner(ALERT, "sre-alice", "RESOLVED", NOW);

        assertThat(affected).isZero();
        assertThat(alertRepository.findById(ALERT).orElseThrow().getStatus()).isEqualTo(AlertStatus.IN_PROGRESS); // 未被动
    }

    @Test
    void endIfOwner_returnsZero_whenAlertNotClaimedYet() {
        seed(ALERT, AlertStatus.DIAGNOSED, null, null);

        int affected = alertRepository.endIfOwner(ALERT, "sre-alice", "RESOLVED", NOW);

        assertThat(affected).isZero();
    }

    @Test
    void endIfOwner_returnsZero_whenAlertAlreadyEnded() {
        seed(ALERT, AlertStatus.RESOLVED, "sre-alice", null);

        int affected = alertRepository.endIfOwner(ALERT, "sre-alice", "CLOSED", NOW);

        assertThat(affected).isZero();
    }

    @Test
    void endIfOwner_returnsZero_whenAlertNotFound() {
        int affected = alertRepository.endIfOwner("NoSuchAlert", "sre-alice", "RESOLVED", NOW);

        assertThat(affected).isZero();
    }
}
