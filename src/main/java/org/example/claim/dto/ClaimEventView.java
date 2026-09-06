package org.example.claim.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.claim.entity.ClaimEvent;

import java.time.Instant;

/**
 * 认领事件视图（读路径，不暴露内部字段）。
 */
@Getter
@Setter
public class ClaimEventView {

    private Long id;
    private String eventType;
    private String operator;
    private Instant createdAt;

    public ClaimEventView() {
    }

    public ClaimEventView(Long id, String eventType, String operator, Instant createdAt) {
        this.id = id;
        this.eventType = eventType;
        this.operator = operator;
        this.createdAt = createdAt;
    }

    public static ClaimEventView from(ClaimEvent event) {
        return new ClaimEventView(event.getId(), event.getEventType(), event.getOperator(), event.getCreatedAt());
    }
}
