package org.example.claim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.example.claim.repository.AlertRepository;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AIOps 前置薄记录（R4 接缝）：复用 QueryMetricsTools 当前告警 feed，
 * 把本次诊断面对的每条告警幂等标记为 DIAGNOSED（可被认领）。
 *
 * 规则：不存在则插入 DIAGNOSED；已存在【绝不降级】——只刷新 lastDiagnosedAt，
 * status / claimed_by 保持现状，因此重复诊断不会撤销已认领状态。
 * 失败静默（log warn）：记录不得阻断 AIOps 主流程。
 */
@Service
public class AlertDiagnosisRecorder {

    private static final Logger logger = LoggerFactory.getLogger(AlertDiagnosisRecorder.class);

    private final AlertRepository alertRepository;
    private final QueryMetricsTools queryMetricsTools;
    private final ObjectMapper objectMapper;

    public AlertDiagnosisRecorder(AlertRepository alertRepository,
                                  QueryMetricsTools queryMetricsTools,
                                  ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.queryMetricsTools = queryMetricsTools;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordCurrentAlerts() {
        try {
            String feed = queryMetricsTools.queryPrometheusAlerts();
            Set<String> names = parseAlertNames(feed);
            Instant now = Instant.now();
            for (String name : names) {
                upsertDiagnosed(name, now);
            }
            if (!names.isEmpty()) {
                logger.info("已记录 {} 条待认领告警: {}", names.size(), names);
            }
        } catch (Exception e) {
            logger.warn("记录诊断告警失败，不影响本次 AIOps 主流程: {}", e.getMessage());
        }
    }

    private Set<String> parseAlertNames(String feedJson) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(feedJson);
            JsonNode alerts = root.path("alerts");
            if (alerts.isArray()) {
                for (JsonNode node : alerts) {
                    String name = node.path("alert_name").asText(null);
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("解析告警 feed 失败，视为无告警: {}", e.getMessage());
        }
        return names;
    }

    private void upsertDiagnosed(String alertName, Instant now) {
        Alert existing = alertRepository.findById(alertName).orElse(null);
        if (existing == null) {
            Alert fresh = new Alert();
            fresh.setAlertName(alertName);
            fresh.setStatus(AlertStatus.DIAGNOSED);
            fresh.setLastDiagnosedAt(now);
            alertRepository.save(fresh);
        } else {
            // 绝不降级：已认领/已结束的告警保持原状，只刷新诊断时间
            existing.setLastDiagnosedAt(now);
            alertRepository.save(existing);
        }
    }
}
