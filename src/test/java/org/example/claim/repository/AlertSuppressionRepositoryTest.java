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
 * US2 抑制窗口 CAS：仅「处理中且本人负责」的告警可设置/取消抑制（条件 UPDATE，受影响行数判成败）。
 * 镜像 AlertRepository.claimIfDiagnosed 的并发语义：行锁 + 谓词重估，0 行=守卫分支。
 *
 * ⚠️ 断言用固定 ISO 时刻（微秒对齐）：H2 TIMESTAMP 存储精度为微秒，Instant.now() 派生值作基线
 * 会被截断致不稳定测试（本文件首版即被 200ns 截断坑过）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AlertSuppressionRepositoryTest {

    @Autowired
    private AlertRepository alertRepository;

    private static final String ALERT = "HighCPUUsage";
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-09-07T13:00:00.123456Z");

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
    void setSuppression_returnsOne_setsWindow_forOwnerOfInProgress() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-alice", null);

        int affected = alertRepository.setSuppression(ALERT, "sre-alice", UNTIL, NOW);

        assertThat(affected).isEqualTo(1);
        assertThat(alertRepository.findById(ALERT).orElseThrow().getSuppressedUntil()).isEqualTo(UNTIL);
    }

    @Test
    void setSuppression_returnsOne_overwritesExistingWindow() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-alice", Instant.parse("2026-09-07T12:30:00Z"));

        int affected = alertRepository.setSuppression(ALERT, "sre-alice", UNTIL, NOW);

        assertThat(affected).isEqualTo(1);
        assertThat(alertRepository.findById(ALERT).orElseThrow().getSuppressedUntil()).isEqualTo(UNTIL);
    }

    @Test
    void setSuppression_returnsOne_withNullUntil_cancelsWindow() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-alice", UNTIL);

        int affected = alertRepository.setSuppression(ALERT, "sre-alice", null, NOW);

        assertThat(affected).isEqualTo(1);
        assertThat(alertRepository.findById(ALERT).orElseThrow().getSuppressedUntil()).isNull();
    }

    @Test
    void setSuppression_returnsZero_whenNotOwner() {
        seed(ALERT, AlertStatus.IN_PROGRESS, "sre-bob", null);

        int affected = alertRepository.setSuppression(ALERT, "sre-alice", UNTIL, NOW);

        assertThat(affected).isZero();
        assertThat(alertRepository.findById(ALERT).orElseThrow().getSuppressedUntil()).isNull(); // 未被动
    }

    @Test
    void setSuppression_returnsZero_whenAlertNotClaimedYet() {
        seed(ALERT, AlertStatus.DIAGNOSED, null, null);

        int affected = alertRepository.setSuppression(ALERT, "sre-alice", UNTIL, NOW);

        assertThat(affected).isZero();
    }

    @Test
    void setSuppression_returnsZero_whenAlertEnded() {
        seed(ALERT, AlertStatus.RESOLVED, "sre-alice", null);

        int affected = alertRepository.setSuppression(ALERT, "sre-alice", UNTIL, NOW);

        assertThat(affected).isZero();
    }

    @Test
    void setSuppression_returnsZero_whenAlertNotFound() {
        int affected = alertRepository.setSuppression("NoSuchAlert", "sre-alice", UNTIL, NOW);

        assertThat(affected).isZero();
    }
}
