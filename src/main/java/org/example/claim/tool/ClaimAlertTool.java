package org.example.claim.tool;

import org.example.claim.dto.AlertClaimException;
import org.example.claim.dto.AlertView;
import org.example.claim.service.AlertClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * claimAlert 聊天工具（agent-tool.md D1/D3/D4/D5/D6）：让值班 SRE 在聊天 agent 里直接「认领」一条已诊断告警。
 *
 * 包放 {@code claim.tool} 而非 {@code agent.tool}：已存在边 claim.service → agent.tool（Recorder→QueryMetricsTools），
 * 放 agent.tool 会造出代码库第一个双向包环；claim.tool 与 claim 有界模块自洽（它就是 claim 领域能力的 agent 暴露面）。
 *
 * 设计落点（详见 specs/001-alert-claim/contracts/agent-tool.md）：
 * - D1 薄适配器：唯一逻辑是调 {@link AlertClaimService#claim}（CAS 单一来源），不加业务。
 * - D3 默认不注册：{@code @ConditionalOnProperty(agent.claim-tool-enabled)} 真注解（QueryLogsTools 宣称有实则无的教训）；
 *   缺省 bean 不存在，同时堵住 ToolCallbackProvider 自动扫描泄漏——模型默认连这个工具都看不见。
 * - D4 operator 由部署绑定（{@code agent.claim-operator}），签名不收 operator：模型无表达身份的通道，
 *   杜绝「任何对话可冒名」对 spec Assumptions 已知局限的放大。多用户/RBAC 归后续切片，此处配置为 V1 占位。
 * - D5 只回 ≤500 字符可读文案；业务异常/运行时异常全部捕获转文案，绝不抛出（LLM 工具容错）。
 * - D6 审计复用 claim_events（claim 内部已写 CLAIM 事件），本工具零新增写。
 */
@Component
@ConditionalOnProperty(name = "agent.claim-tool-enabled", havingValue = "true")
public class ClaimAlertTool {

    private static final Logger logger = LoggerFactory.getLogger(ClaimAlertTool.class);

    private final AlertClaimService claimService;
    private final String dutyOperator;

    public ClaimAlertTool(AlertClaimService claimService,
                          @Value("${agent.claim-operator:}") String dutyOperator) {
        this.claimService = claimService;
        this.dutyOperator = dutyOperator;
    }

    /**
     * 认领一条已诊断且未被他人认领的告警（先到先得、锁定到处理结束）。
     */
    @Tool(description = "认领一条已诊断且未被他人认领的告警（值班负责人先到先得、锁定到处理结束）。"
            + "当用户说「认领 / 我来负责 / 我接手某条告警」时调用；以本工位值班负责人名义认领，"
            + "无需也不接受用户指定负责人。参数 alertName = 告警名称（如 HighCPUUsage），"
            + "可先用 queryPrometheusAlerts 确认告警状态。")
    public String claimAlert(@ToolParam(description = "告警名称，如 HighCPUUsage") String alertName) {
        // D4 fail-closed：未配置值班负责人则不动作，宁可认领不了也不猜测身份
        if (dutyOperator == null || dutyOperator.isBlank()) {
            logger.warn("claimAlert 被调用但未配置 agent.claim-operator，拒绝认领 {}", alertName);
            return "认领失败：未配置值班负责人（agent.claim-operator），无法认领";
        }

        try {
            AlertView view = claimService.claim(alertName, dutyOperator);
            return "认领成功：" + view.getAlertName() + " 状态 " + view.getStatus()
                    + "，负责人 " + view.getClaimedBy() + " @" + view.getClaimedAt();
        } catch (AlertClaimException e) {
            // D5：业务守卫（40401/40901/…）转可读文案，与 REST 同一来源
            return "认领失败：" + e.getMessage();
        } catch (Exception e) {
            logger.error("claimAlert 认领 {} 发生内部错误", alertName, e);
            return "认领失败（内部错误），请稍后重试";
        }
    }
}
