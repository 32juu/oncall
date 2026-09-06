package org.example.claim.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 事件历史响应：{ "events": [...] }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertEventsResponse {

    private List<ClaimEventView> events;
}
