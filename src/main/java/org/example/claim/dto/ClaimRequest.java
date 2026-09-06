package org.example.claim.dto;

import lombok.Data;

/**
 * 认领请求体：{ "operator": "sre-alice" }。operator = 值班人自报标识（v1 不认证，见 spec Assumptions）。
 */
@Data
public class ClaimRequest {

    private String operator;
}
