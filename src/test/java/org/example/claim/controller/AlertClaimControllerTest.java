package org.example.claim.controller;

import org.example.claim.dto.AlertClaimException;
import org.example.claim.dto.AlertEventsResponse;
import org.example.claim.dto.AlertListResponse;
import org.example.claim.dto.AlertView;
import org.example.claim.dto.ClaimEventView;
import org.example.claim.dto.ErrorCode;
import org.example.claim.entity.AlertStatus;
import org.example.claim.service.AlertClaimService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层契约：路径/方法/状态码/信封。映射逻辑由 service 抛错驱动，service 以 @MockBean 隔离。
 */
@WebMvcTest(AlertClaimController.class)
class AlertClaimControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertClaimService alertClaimService;

    private AlertView inProgress(String owner) {
        AlertView v = new AlertView();
        v.setAlertName("HighCPUUsage");
        v.setStatus(AlertStatus.IN_PROGRESS);
        v.setClaimedBy(owner);
        v.setClaimedAt(Instant.now());
        v.setLastDiagnosedAt(Instant.now());
        return v;
    }

    @Test
    void claim_success_returns200AndView() throws Exception {
        when(alertClaimService.claim(eq("HighCPUUsage"), eq("sre-alice"))).thenReturn(inProgress("sre-alice"));

        mockMvc.perform(post("/api/alerts/HighCPUUsage/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"sre-alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.alertName").value("HighCPUUsage"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.claimedBy").value("sre-alice"));
    }

    @Test
    void claim_blankOperator_returns400() throws Exception {
        when(alertClaimService.claim(eq("HighCPUUsage"), isNull()))
                .thenThrow(new AlertClaimException(ErrorCode.BLANK_OPERATOR));

        mockMvc.perform(post("/api/alerts/HighCPUUsage/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void claim_rejectedOther_returns409WithOwner() throws Exception {
        when(alertClaimService.claim(eq("HighCPUUsage"), eq("sre-bob")))
                .thenThrow(new AlertClaimException(ErrorCode.ALERT_ALREADY_CLAIMED,
                        "该告警已被 sre-alice 负责，无法重复认领"));

        mockMvc.perform(post("/api/alerts/HighCPUUsage/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"sre-bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value("该告警已被 sre-alice 负责，无法重复认领"));
    }

    @Test
    void claim_unknownAlert_returns404() throws Exception {
        when(alertClaimService.claim(eq("NoSuchAlert"), eq("sre-alice")))
                .thenThrow(new AlertClaimException(ErrorCode.ALERT_NOT_FOUND));

        mockMvc.perform(post("/api/alerts/NoSuchAlert/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"sre-alice\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void get_returnsOwnerVisibility() throws Exception {
        when(alertClaimService.get("HighCPUUsage")).thenReturn(inProgress("sre-alice"));

        mockMvc.perform(get("/api/alerts/HighCPUUsage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.claimedBy").value("sre-alice"));
    }

    @Test
    void list_withStatusFilter() throws Exception {
        when(alertClaimService.list(AlertStatus.DIAGNOSED)).thenReturn(List.of());
        mockMvc.perform(get("/api/alerts").param("status", "DIAGNOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alerts").isArray());
    }

    @Test
    void list_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/alerts").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void events_returnsTimeline() throws Exception {
        when(alertClaimService.get("HighCPUUsage")).thenReturn(inProgress("sre-alice"));
        ClaimEventView ev = new ClaimEventView();
        ev.setId(1L);
        ev.setEventType("CLAIM");
        ev.setOperator("sre-alice");
        ev.setCreatedAt(Instant.now());
        when(alertClaimService.events("HighCPUUsage")).thenReturn(List.of(ev));

        mockMvc.perform(get("/api/alerts/HighCPUUsage/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[0].eventType").value("CLAIM"));
    }
}
