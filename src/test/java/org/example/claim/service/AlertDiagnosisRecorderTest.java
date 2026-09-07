package org.example.claim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.example.claim.repository.AlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * R4 接缝语义：recorder 把 feed 里的告警幂等标记为 DIAGNOSED；对已认领/已结束告警【绝不降级】。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AlertDiagnosisRecorderTest {

    private static final String CPU = "HighCPUUsage";

    @Autowired
    private AlertRepository alertRepository;

    @MockBean
    private QueryMetricsTools queryMetricsTools;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AlertDiagnosisRecorder recorder() {
        return new AlertDiagnosisRecorder(alertRepository, queryMetricsTools, objectMapper);
    }

    private void seed(String alertName, AlertStatus status, String claimedBy, Instant lastDiagnosedAt) {
        seed(alertName, status, claimedBy, lastDiagnosedAt, null);
    }

    private void seed(String alertName, AlertStatus status, String claimedBy, Instant lastDiagnosedAt,
                      Instant suppressedUntil) {
        Alert a = new Alert();
        a.setAlertName(alertName);
        a.setStatus(status);
        a.setClaimedBy(claimedBy);
        a.setLastDiagnosedAt(lastDiagnosedAt);
        a.setSuppressedUntil(suppressedUntil);
        alertRepository.save(a);
    }

    @Test
    void record_createsDiagnosedRows_forUnknownAlerts() {
        when(queryMetricsTools.queryPrometheusAlerts()).thenReturn(
                "{\"success\":true,\"alerts\":[{\"alert_name\":\"HighCPUUsage\"},{\"alert_name\":\"HighMemoryUsage\"}],\"message\":\"ok\"}");

        recorder().recordCurrentAlerts();

        assertThat(alertRepository.findById(CPU)).isPresent();
        assertThat(alertRepository.findById(CPU).orElseThrow().getStatus()).isEqualTo(AlertStatus.DIAGNOSED);
        assertThat(alertRepository.findById(CPU).orElseThrow().getLastDiagnosedAt()).isNotNull();
        assertThat(alertRepository.findById("HighMemoryUsage")).isPresent();
    }

    @Test
    void record_neverDowngrades_claimedAlert() {
        Instant old = Instant.now().minusSeconds(60);
        seed(CPU, AlertStatus.IN_PROGRESS, "sre-alice", old);
        when(queryMetricsTools.queryPrometheusAlerts()).thenReturn(
                "{\"success\":true,\"alerts\":[{\"alert_name\":\"HighCPUUsage\"}],\"message\":\"ok\"}");

        recorder().recordCurrentAlerts();

        Alert after = alertRepository.findById(CPU).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS); // 未降级
        assertThat(after.getClaimedBy()).isEqualTo("sre-alice");
        assertThat(after.getLastDiagnosedAt()).isAfterOrEqualTo(old);    // 仅刷新诊断时间
    }

    @Test
    void record_ignoresParseFailure_withoutThrowing() {
        when(queryMetricsTools.queryPrometheusAlerts()).thenReturn("this is not json");

        assertThatCode(() -> recorder().recordCurrentAlerts()).doesNotThrowAnyException();
        assertThat(alertRepository.count()).isZero();
    }

    // ============ US2 抑制窗口闸 ============
    // ⚠️ 固定远未来/远过去时刻：避免「相对现在的时刻」随墙上时钟变陈旧导致活动性误判。
    private static final Instant OLD = Instant.parse("2000-01-01T00:00:00Z");      // 远过去：非活动窗口 / 诊断基线
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");   // 远未来：活动窗口

    @Test
    void record_skipsRefresh_whileSuppressionActive() {
        // 抑制窗口生效中（until 在未来）：recorder 不得刷新诊断时间（FR-008 静音期内不产生新诊断）
        seed(CPU, AlertStatus.IN_PROGRESS, "sre-alice", OLD, FUTURE);
        when(queryMetricsTools.queryPrometheusAlerts()).thenReturn(
                "{\"success\":true,\"alerts\":[{\"alert_name\":\"HighCPUUsage\"}],\"message\":\"ok\"}");

        recorder().recordCurrentAlerts();

        Alert after = alertRepository.findById(CPU).orElseThrow();
        assertThat(after.getLastDiagnosedAt()).isEqualTo(OLD);   // 未被刷新 = 跳过诊断记录
        assertThat(after.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
        assertThat(after.getSuppressedUntil()).isEqualTo(FUTURE); // 窗口原样保留
    }

    @Test
    void record_resumesRefresh_afterWindowExpires() {
        // 窗口已到点（until 在过去，惰性失效）：恢复正常诊断刷新；状态仍不降级
        seed(CPU, AlertStatus.IN_PROGRESS, "sre-alice", OLD, Instant.parse("2020-01-01T00:00:00Z"));
        when(queryMetricsTools.queryPrometheusAlerts()).thenReturn(
                "{\"success\":true,\"alerts\":[{\"alert_name\":\"HighCPUUsage\"}],\"message\":\"ok\"}");

        recorder().recordCurrentAlerts();

        Alert after = alertRepository.findById(CPU).orElseThrow();
        assertThat(after.getLastDiagnosedAt()).isAfter(OLD);     // 恢复刷新
        assertThat(after.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS); // 仍不降级
        assertThat(after.getClaimedBy()).isEqualTo("sre-alice");
    }
}
