package org.example.claim.controller;

import org.example.claim.dto.AlertClaimException;
import org.example.claim.dto.AlertEventsResponse;
import org.example.claim.dto.AlertListResponse;
import org.example.claim.dto.AlertView;
import org.example.claim.dto.ApiResponse;
import org.example.claim.dto.ClaimRequest;
import org.example.claim.dto.DispositionRequest;
import org.example.claim.dto.ErrorCode;
import org.example.claim.dto.SuppressRequest;
import org.example.claim.entity.AlertStatus;
import org.example.claim.service.AlertClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认领闭环 REST 端点（契约见 specs/001-alert-claim/contracts/api.md）。
 * 与遗留 controller 不同：本模块按真实 HTTP 状态码返回（4xx/5xx），body 内仍带 {code,message,data}。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertClaimController {

    private static final Logger logger = LoggerFactory.getLogger(AlertClaimController.class);

    private final AlertClaimService alertClaimService;

    public AlertClaimController(AlertClaimService alertClaimService) {
        this.alertClaimService = alertClaimService;
    }

    /** 认领一条已诊断告警（先到先得、防重复接管） */
    @PostMapping("/{alertName}/claim")
    public ApiResponse<AlertView> claim(@PathVariable String alertName,
                                        @RequestBody(required = false) ClaimRequest request) {
        String operator = (request == null) ? null : request.getOperator();
        return ApiResponse.success(alertClaimService.claim(alertName, operator));
    }

    /** 设置抑制窗口（US2 FR-009）：仅当前负责人对处理中告警可设；until 必须晚于当前；覆盖式替换 */
    @PostMapping("/{alertName}/suppress")
    public ApiResponse<AlertView> suppress(@PathVariable String alertName,
                                           @RequestBody(required = false) SuppressRequest request) {
        SuppressRequest req = (request == null) ? new SuppressRequest() : request;
        return ApiResponse.success(alertClaimService.suppress(alertName, req.getOperator(), req.getUntil()));
    }

    /** 取消抑制窗口（US2 FR-010）：仅当前负责人可取消；本无窗口视为成功（幂等）。
     *  operator 缺省放行 null → service 抛 BLANK_OPERATOR(40001)，与 claim 空 body 同构（守卫集中在 service）。 */
    @DeleteMapping("/{alertName}/suppress")
    public ApiResponse<AlertView> cancelSuppression(@PathVariable String alertName,
                                                    @RequestParam(name = "operator", required = false) String operator) {
        return ApiResponse.success(alertClaimService.cancelSuppression(alertName, operator));
    }

    /** 处置终局（US3 FR-010/FR-011）：当前负责人对处理中告警记录动作并进入 RESOLVED/CLOSED（一次调用即终局） */
    @PostMapping("/{alertName}/disposition")
    public ApiResponse<AlertView> disposition(@PathVariable String alertName,
                                              @RequestBody(required = false) DispositionRequest request) {
        DispositionRequest req = (request == null) ? new DispositionRequest() : request;
        return ApiResponse.success(alertClaimService.recordDisposition(
                alertName, req.getOperator(), req.getOutcome(), req.getAction()));
    }

    /** 查询单条告警（负责人可见性 FR-003） */
    @GetMapping("/{alertName}")
    public ApiResponse<AlertView> get(@PathVariable String alertName) {
        return ApiResponse.success(alertClaimService.get(alertName));
    }

    /** 列出告警，可按状态过滤 */
    @GetMapping
    public ApiResponse<AlertListResponse> list(@RequestParam(name = "status", required = false) String status) {
        AlertStatus parsed = parseStatus(status);
        return ApiResponse.success(new AlertListResponse(alertClaimService.list(parsed)));
    }

    /** 事件历史（审计/时间线，只读） */
    @GetMapping("/{alertName}/events")
    public ApiResponse<AlertEventsResponse> events(@PathVariable String alertName) {
        return ApiResponse.success(new AlertEventsResponse(alertClaimService.events(alertName)));
    }

    private AlertStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AlertStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AlertClaimException(ErrorCode.INVALID_STATUS_FILTER);
        }
    }

    @ExceptionHandler(AlertClaimException.class)
    public ResponseEntity<ApiResponse<Void>> handleClaimException(AlertClaimException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity
                .status(httpStatus(errorCode))
                .body(ApiResponse.error(errorCode, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        logger.error("告警认领接口未预期异常", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private HttpStatus httpStatus(ErrorCode errorCode) {
        // code 是 4xxxx/5xxxx：整除以 100 得标准三位 HTTP 状态（40901/100=409、40401/100=404…）
        HttpStatus resolved = HttpStatus.resolve(errorCode.getCode() / 100);
        return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
