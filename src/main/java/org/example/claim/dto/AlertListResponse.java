package org.example.claim.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警列表响应：{ "alerts": [...] }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertListResponse {

    private List<AlertView> alerts;
}
