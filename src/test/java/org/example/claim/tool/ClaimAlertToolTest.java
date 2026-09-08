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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * claimAlert 工具单测（agent-tool.md §4 验收）：
 * 纯 Mockito 边界验证 D4/D5（身份 fail-closed、错误转文案不抛）+ ApplicationContextRunner 验证 D3（条件注册，防自动扫描泄漏）。
 * 不依赖 DashScope / MySQL / 真实上下文。
 */
class ClaimAlertToolTest {

    private static final String ALERT = "HighCPUUsage";
    private static final String OPERATOR = "sre-alice";

    // ============ 纯单测：D4/D5（agent-tool.md §4） ============

    @Test
    void claim_success_returnsReadableText() {
        AlertClaimService service = mock(AlertClaimService.class);
        AlertView view = new AlertView();
        view.setAlertName(ALERT);
        view.setStatus(AlertStatus.IN_PROGRESS);
        view.setClaimedBy(OPERATOR);
        view.setClaimedAt(Instant.parse("2026-09-06T10:00:00Z"));
        when(service.claim(ALERT, OPERATOR)).thenReturn(view);

        String result = new ClaimAlertTool(service, OPERATOR).claimAlert(ALERT);

        assertThat(result)
                .contains("认领成功")
                .contains(ALERT)
                .contains("IN_PROGRESS")
                .contains(OPERATOR);
        assertThat(result.length()).isLessThanOrEqualTo(500); // D5 截断
    }

    @Test
    void claim_errorCodes_becomeTextWithoutThrowing() {
        // 守卫分支全覆盖：40401/40901/40902/40903/40001（40002 语义同源，由空 alertName 触发）
        for (ErrorCode code : new ErrorCode[]{
                ErrorCode.ALERT_NOT_FOUND,
                ErrorCode.ALERT_ALREADY_CLAIMED,
                ErrorCode.ALERT_SELF_CLAIMED,
                ErrorCode.ALERT_ENDED,
                ErrorCode.BLANK_OPERATOR}) {
            AlertClaimService service = mock(AlertClaimService.class);
            when(service.claim(ALERT, OPERATOR)).thenThrow(new AlertClaimException(code));

            ClaimAlertTool tool = new ClaimAlertTool(service, OPERATOR);

            assertThatCode(() -> {
                String result = tool.claimAlert(ALERT);
                assertThat(result).contains("认领失败").contains(code.getMessage());
            }).doesNotThrowAnyException(); // D5：不抛给 LLM
        }
    }

    @Test
    void claim_runtimeException_becomesGenericTextWithoutThrowing() {
        AlertClaimService service = mock(AlertClaimService.class);
        when(service.claim(ALERT, OPERATOR)).thenThrow(new IllegalStateException("boom"));

        String result = new ClaimAlertTool(service, OPERATOR).claimAlert(ALERT);

        assertThat(result).contains("认领失败").contains("内部错误");
    }

    @Test
    void claim_blankDutyOperator_failsClosed_neverCallsService() {
        AlertClaimService service = mock(AlertClaimService.class);

        String result = new ClaimAlertTool(service, "   ").claimAlert(ALERT);

        assertThat(result).contains("未配置值班负责人");
        verify(service, never()).claim(anyString(), anyString()); // D4 fail-closed：宁可不动作
    }

    // ============ 条件注册：D3 防自动扫描泄漏（ApplicationContextRunner） ============

    @Configuration
    @ComponentScan(basePackages = "org.example.claim.tool",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = ClaimAlertTool.class))
    static class ClaimToolScan {}

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ClaimToolScan.class)
                .withBean(AlertClaimService.class, () -> mock(AlertClaimService.class));
    }

    @Test
    void bean_absent_whenPropertyMissing() {
        runner().run(ctx -> assertThat(ctx.getBeansOfType(ClaimAlertTool.class)).isEmpty());
    }

    @Test
    void bean_absent_whenPropertyFalse() {
        runner().withPropertyValues("agent.claim-tool-enabled=false")
                .run(ctx -> assertThat(ctx.getBeansOfType(ClaimAlertTool.class)).isEmpty());
    }

    @Test
    void bean_present_whenPropertyTrue() {
        runner().withPropertyValues("agent.claim-tool-enabled=true", "agent.claim-operator=sre-alice")
                .run(ctx -> assertThat(ctx.getBeansOfType(ClaimAlertTool.class)).hasSize(1));
    }
}
