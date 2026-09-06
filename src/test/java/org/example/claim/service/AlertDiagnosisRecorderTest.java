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
        Alert a = new Alert();
        a.setAlertName(alertName);
        a.setStatus(status);
        a.setClaimedBy(claimedBy);
        a.setLastDiagnosedAt(lastDiagnosedAt);
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
}
