package org.example.claim.tool;

import org.example.claim.dto.AlertClaimException;
import org.example.claim.dto.AlertView;
import org.example.claim.dto.ErrorCode;
import org.example.claim.entity.AlertStatus;
import org.example.claim.service.AlertClaimService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * suppressAlert / cancelSuppression 工具单测：
 * 纯 Mockito 验证翻译层（D4/D5 + until 归一化）+ ApplicationContextRunner 验证 D3 条件注册（防自动扫描泄漏）。
 * 不依赖 DashScope / MySQL / 真实上下文。
 */
class SuppressAlertToolTest {

    private static final String ALERT = "HighCPUUsage";
    private static final String OPERATOR = "sre-alice";
    private static final Instant UNTIL = Instant.parse("2026-09-08T12:00:00Z");

    private AlertView inProgressView() {
        AlertView view = new AlertView();
        view.setAlertName(ALERT);
        view.setStatus(AlertStatus.IN_PROGRESS);
        view.setClaimedBy(OPERATOR);
        return view;
    }

    // ============ until 翻译层（本工具独有：模型不心算时刻，给相对时长或 ISO） ============

    @Test
    void parseUntil_isoInstant_passthrough() {
        assertThat(SuppressAlertTool.parseUntil("2026-09-08T12:00:00Z")).isEqualTo(UNTIL);
    }

    @Test
    void parseUntil_relativeDuration_anchoredToNow() {
        Instant before = Instant.now();
        Instant parsed = SuppressAlertTool.parseUntil("2h");
        Instant after = Instant.now();

        assertThat(parsed).isNotNull();
        assertThat(parsed).isAfterOrEqualTo(before.plus(2, ChronoUnit.HOURS));
        assertThat(parsed).isBeforeOrEqualTo(after.plus(2, ChronoUnit.HOURS).plusSeconds(1));
    }

    @Test
    void parseUntil_minutesAndDays_resolved() {
        assertThat(SuppressAlertTool.parseUntil("90m")).isNotNull();
        assertThat(SuppressAlertTool.parseUntil("1d")).isNotNull();
    }

    @Test
    void parseUntil_garbage_null() {
        assertThat(SuppressAlertTool.parseUntil("下周二")).isNull();
        assertThat(SuppressAlertTool.parseUntil("")).isNull();
        assertThat(SuppressAlertTool.parseUntil("  ")).isNull();
        assertThat(SuppressAlertTool.parseUntil(null)).isNull();
    }

    // ============ suppressAlert：设置成功（ISO 与相对两条路都走 service） ============

    @Test
    void suppress_success_isoUntil_returnsReadableText() {
        AlertClaimService service = mock(AlertClaimService.class);
        AlertView view = inProgressView();
        view.setSuppressedUntil(UNTIL);
        when(service.suppress(ALERT, OPERATOR, UNTIL)).thenReturn(view);

        String result = new SuppressAlertTool(service, OPERATOR).suppressAlert(ALERT, "2026-09-08T12:00:00Z");

        assertThat(result)
                .contains("设置抑制窗口成功")
                .contains(ALERT)
                .contains("IN_PROGRESS")
                .contains(OPERATOR)
                .contains("12:00:00Z");
        assertThat(result.length()).isLessThanOrEqualTo(500); // D5 截断
        verify(service).suppress(ALERT, OPERATOR, UNTIL);
    }

    @Test
    void suppress_success_relativeUntil_forwardedAsFutureInstant() {
        AlertClaimService service = mock(AlertClaimService.class);
        AlertView view = inProgressView();
        view.setSuppressedUntil(Instant.now().plus(2, ChronoUnit.HOURS));
        when(service.suppress(anyString(), anyString(), any(Instant.class))).thenReturn(view);

        String result = new SuppressAlertTool(service, OPERATOR).suppressAlert(ALERT, "2h");

        assertThat(result).contains("设置抑制窗口成功").contains(ALERT);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(service).suppress(eq(ALERT), eq(OPERATOR), until.capture());
        // 相对时长锚定 now：捕获的 until ≈ now+2h（容差 ±1 秒避免边界抖动）
        assertThat(until.getValue())
                .isBetween(Instant.now().plus(2, ChronoUnit.HOURS).minusSeconds(1),
                        Instant.now().plus(2, ChronoUnit.HOURS).plusSeconds(1));
    }

    @Test
    void suppress_untilUnparseable_neverCallsService() {
        AlertClaimService service = mock(AlertClaimService.class);

        String result = new SuppressAlertTool(service, OPERATOR).suppressAlert(ALERT, "下周二");

        assertThat(result).contains("设置抑制窗口失败").contains("无法解析");
        verify(service, never()).suppress(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void suppress_errorCodes_becomeTextWithoutThrowing() {
        // 抑制守卫分支：40004(until 不晚于当前) / 40401 / 40903(已结束) / 40904(未认领) / 40905(非负责人)
        for (ErrorCode code : new ErrorCode[]{
                ErrorCode.INVALID_UNTIL,
                ErrorCode.ALERT_NOT_FOUND,
                ErrorCode.ALERT_ENDED,
                ErrorCode.ALERT_NOT_CLAIMED,
                ErrorCode.SUPPRESS_NOT_OWNER}) {
            AlertClaimService service = mock(AlertClaimService.class);
            when(service.suppress(ALERT, OPERATOR, UNTIL)).thenThrow(new AlertClaimException(code));

            SuppressAlertTool tool = new SuppressAlertTool(service, OPERATOR);

            assertThatCode(() -> {
                String result = tool.suppressAlert(ALERT, "2026-09-08T12:00:00Z");
                assertThat(result).contains("设置抑制窗口失败").contains(code.getMessage());
            }).doesNotThrowAnyException(); // D5：不抛给 LLM
        }
    }

    @Test
    void suppress_runtimeException_becomesGenericTextWithoutThrowing() {
        AlertClaimService service = mock(AlertClaimService.class);
        when(service.suppress(ALERT, OPERATOR, UNTIL)).thenThrow(new IllegalStateException("boom"));

        String result = new SuppressAlertTool(service, OPERATOR).suppressAlert(ALERT, "2026-09-08T12:00:00Z");

        assertThat(result).contains("设置抑制窗口失败").contains("内部错误");
    }

    // ============ cancelSuppression：取消成功 / 守卫 / 幂等 ============

    @Test
    void cancel_success_returnsReadableText_noActiveWindow() {
        AlertClaimService service = mock(AlertClaimService.class);
        AlertView view = inProgressView(); // suppressedUntil 为 null = 已取消
        when(service.cancelSuppression(ALERT, OPERATOR)).thenReturn(view);

        String result = new SuppressAlertTool(service, OPERATOR).cancelSuppression(ALERT);

        assertThat(result)
                .contains("取消抑制窗口成功")
                .contains(ALERT)
                .contains("IN_PROGRESS")
                .contains("无活动抑制窗口");
        assertThat(result.length()).isLessThanOrEqualTo(500);
        verify(service).cancelSuppression(ALERT, OPERATOR);
    }

    @Test
    void cancel_errorCodes_becomeTextWithoutThrowing() {
        for (ErrorCode code : new ErrorCode[]{
                ErrorCode.ALERT_NOT_FOUND,
                ErrorCode.ALERT_ENDED,
                ErrorCode.ALERT_NOT_CLAIMED,
                ErrorCode.SUPPRESS_NOT_OWNER}) {
            AlertClaimService service = mock(AlertClaimService.class);
            when(service.cancelSuppression(ALERT, OPERATOR)).thenThrow(new AlertClaimException(code));

            SuppressAlertTool tool = new SuppressAlertTool(service, OPERATOR);

            assertThatCode(() -> {
                String result = tool.cancelSuppression(ALERT);
                assertThat(result).contains("取消抑制窗口失败").contains(code.getMessage());
            }).doesNotThrowAnyException();
        }
    }

    @Test
    void cancel_runtimeException_becomesGenericTextWithoutThrowing() {
        AlertClaimService service = mock(AlertClaimService.class);
        when(service.cancelSuppression(ALERT, OPERATOR)).thenThrow(new IllegalStateException("boom"));

        String result = new SuppressAlertTool(service, OPERATOR).cancelSuppression(ALERT);

        assertThat(result).contains("取消抑制窗口失败").contains("内部错误");
    }

    // ============ D4 fail-closed：空 operator 两个方法都拒绝且不碰 service ============

    @Test
    void blankDutyOperator_suppress_failsClosed_neverCallsService() {
        AlertClaimService service = mock(AlertClaimService.class);

        String result = new SuppressAlertTool(service, "   ").suppressAlert(ALERT, "2h");

        assertThat(result).contains("未配置值班负责人");
        verify(service, never()).suppress(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void blankDutyOperator_cancel_failsClosed_neverCallsService() {
        AlertClaimService service = mock(AlertClaimService.class);

        String result = new SuppressAlertTool(service, "   ").cancelSuppression(ALERT);

        assertThat(result).contains("未配置值班负责人");
        verify(service, never()).cancelSuppression(anyString(), anyString());
    }

    // ============ D3 条件注册：防自动扫描泄漏（ApplicationContextRunner） ============

    @Configuration
    @ComponentScan(basePackages = "org.example.claim.tool",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = SuppressAlertTool.class))
    static class SuppressToolScan {}

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(SuppressToolScan.class)
                .withBean(AlertClaimService.class, () -> mock(AlertClaimService.class));
    }

    @Test
    void bean_absent_whenPropertyMissing() {
        runner().run(ctx -> assertThat(ctx.getBeansOfType(SuppressAlertTool.class)).isEmpty());
    }

    @Test
    void bean_absent_whenPropertyFalse() {
        runner().withPropertyValues("agent.claim-tool-enabled=false")
                .run(ctx -> assertThat(ctx.getBeansOfType(SuppressAlertTool.class)).isEmpty());
    }

    @Test
    void bean_present_whenPropertyTrue() {
        runner().withPropertyValues("agent.claim-tool-enabled=true", "agent.claim-operator=sre-alice")
                .run(ctx -> assertThat(ctx.getBeansOfType(SuppressAlertTool.class)).hasSize(1));
    }
}
