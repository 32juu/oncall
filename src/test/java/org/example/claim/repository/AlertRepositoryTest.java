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
 * 核心并发不变量测试：认领 = 条件 UPDATE（WHERE status='DIAGNOSED'），
 * 恰好 1 行受影响才成功。本测试驱动 AlertRepository.claimIfDiagnosed 的实现。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 用 src/test H2(MODE=MySQL)
class AlertRepositoryTest {

    @Autowired
    private AlertRepository alertRepository;

    private static final String ALERT = "HighCPUUsage";

    private void seed(String alertName, AlertStatus status, String claimedBy) {
        Alert alert = new Alert();
        alert.setAlertName(alertName);
        alert.setStatus(status);
        alert.setClaimedBy(claimedBy);
        alert.setLastDiagnosedAt(Instant.now());
        alertRepository.save(alert);
    }

    @BeforeEach
    void cleanUp() {
        alertRepository.deleteAll();
    }

    @Test
    void claimIfDiagnosed_returnsOne_whenStatusIsDiagnosed() {
        seed(ALERT, AlertStatus.DIAGNOSED, null);

        int affected = alertRepository.claimIfDiagnosed(ALERT, "sre-alice", Instant.now());

        assertThat(affected).isEqualTo(1);
        Alert after = alertRepository.findById(ALERT).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
        assertThat(after.getClaimedBy()).isEqualTo("sre-alice");
    }

    @Test
    void claimIfDiagnosed_returnsZero_whenStatusIsInProgress() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-bob");

        int affected = alertRepository.claimIfDiagnosed(ALERT, "sre-alice", Instant.now());

        assertThat(affected).isZero();
        Alert after = alertRepository.findById(ALERT).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
        assertThat(after.getClaimedBy()).isEqualTo("sre-bob"); // 未被覆盖
    }

    @Test
    void claimIfDiagnosed_returnsZero_whenAlertEnded() {
        seed(ALERT, AlertStatus.RESOLVED, "sre-bob");

        int affected = alertRepository.claimIfDiagnosed(ALERT, "sre-alice", Instant.now());

        assertThat(affected).isZero();
    }

    @Test
    void claimIfDiagnosed_returnsZero_whenAlertNotFound() {
        int affected = alertRepository.claimIfDiagnosed("NoSuchAlert", "sre-alice", Instant.now());

        assertThat(affected).isZero();
    }
}
