package org.example.claim.tool;

import org.example.agent.tool.ToolLevel;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抑制窗口聊天工具（suppressAlert / cancelSuppression）—— claimAlert 的姊妹工具，设计配方同 agent-tool.md：
 * D1 薄适配器（唯一依赖 AlertClaimService，不重写 CAS）；D3 @ConditionalOnProperty 缺省不注册；
 * D4 operator 由部署配置绑定、签名不收参数；D5 只回 ≤500 字可读文案、不抛给 LLM。
 * 唯一新增：until 翻译层——模型不心算"现在+2h"，接受 ISO-8601 时刻或相对时长（2h/90m/1d）两种写法。
 */
@Component
@ConditionalOnProperty(name = "agent.claim-tool-enabled", havingValue = "true")
@ToolLevel(ToolLevel.Level.WRITE)  // D2 分级：设置/取消抑制会改状态 → 同 claimAlert，只进聊天槽位（ToolRegistry.readOnlyOnly 会从 AIOps 过滤掉）
public class SuppressAlertTool {

    private static final Logger logger = LoggerFactory.getLogger(SuppressAlertTool.class);
    private static final Pattern RELATIVE_UNTIL = Pattern.compile("^(\\d{1,4})\\s*([mhd])$", Pattern.CASE_INSENSITIVE);

    private final AlertClaimService claimService;
    private final String dutyOperator;

    public SuppressAlertTool(AlertClaimService claimService,
                             @Value("${agent.claim-operator:}") String dutyOperator) {
        this.claimService = claimService;
        this.dutyOperator = dutyOperator;
    }

    @Tool(description = "为一条已认领且由本工位值班负责人处理的告警设置抑制窗口：抑制期内该告警再次触发不再产生新的重复诊断记录（到点自动恢复）。"
            + "当用户说「抑制 / 静默 / 先不重复打扰某条告警」「暂缓 N 小时」时调用；仅能作用于本人负责的 IN_PROGRESS 告警"
            + "（可先用 queryPrometheusAlerts 查看状态，未认领先 claimAlert）。"
            + "参数 alertName = 告警名称（如 HighCPUUsage）；"
            + "参数 until = 抑制结束时刻，给相对时长（如 2h、90m、1d，自当前起算）或 ISO-8601 时刻（如 2026-09-08T12:00:00Z），"
            + "若用户只说「到明天」「晚上八点」等模糊时间，先澄清具体时长或时刻再调用。")
    public String suppressAlert(@ToolParam(description = "告警名称，如 HighCPUUsage") String alertName,
                                @ToolParam(description = "抑制结束时刻：相对时长如 2h / 90m / 1d，或 ISO-8601 时刻如 2026-09-08T12:00:00Z") String until) {
        String blocked = blockedReason();
        if (blocked != null) {
            return blocked;
        }
        Instant end = parseUntil(until);
        if (end == null) {
            return "设置抑制窗口失败：无法解析抑制结束时刻「" + until + "」，请提供 ISO 时刻（如 2026-09-08T12:00:00Z）或相对时长（如 2h、90m、1d）";
        }
        try {
            AlertView view = claimService.suppress(alertName, dutyOperator, end);
            return "设置抑制窗口成功：" + view.getAlertName() + " 状态 " + view.getStatus()
                    + "，负责人 " + view.getClaimedBy() + "，抑制至 " + view.getSuppressedUntil();
        } catch (AlertClaimException e) {
            return "设置抑制窗口失败：" + e.getMessage();
        } catch (Exception e) {
            logger.error("suppressAlert 设置抑制窗口 {} 发生内部错误", alertName, e);
            return "设置抑制窗口失败（内部错误），请稍后重试";
        }
    }

    @Tool(description = "取消某条告警当前的抑制窗口，恢复该告警再次触发时的诊断提示（幂等：本无窗口也提示成功、不产生审计事件）。"
            + "当用户说「取消抑制 / 恢复告警提醒 / 不再抑制某条告警」时调用；仅能作用于本人负责的 IN_PROGRESS 告警。"
            + "参数 alertName = 告警名称（如 HighCPUUsage）。")
    public String cancelSuppression(@ToolParam(description = "告警名称，如 HighCPUUsage") String alertName) {
        String blocked = blockedReason();
        if (blocked != null) {
            return blocked;
        }
        try {
            AlertView view = claimService.cancelSuppression(alertName, dutyOperator);
            return "取消抑制窗口成功：" + view.getAlertName() + " 状态 " + view.getStatus()
                    + "，负责人 " + view.getClaimedBy() + "，已无活动抑制窗口";
        } catch (AlertClaimException e) {
            return "取消抑制窗口失败：" + e.getMessage();
        } catch (Exception e) {
            logger.error("cancelSuppression 取消 {} 的抑制窗口发生内部错误", alertName, e);
            return "取消抑制窗口失败（内部错误），请稍后重试";
        }
    }

    /** D4 fail-closed：未配置值班负责人 → 拒绝一切写操作。 */
    private String blockedReason() {
        if (dutyOperator == null || dutyOperator.isBlank()) {
            logger.warn("抑制窗口工具被调用但未配置 agent.claim-operator，拒绝操作");
            return "操作失败：未配置值班负责人（agent.claim-operator），无法设置或取消抑制窗口";
        }
        return null;
    }

    /** until 翻译层：ISO-8601 时刻 或 相对时长（m=分钟/h=小时/d=天，自当前起算）；两者都不像 → null（调用方转文案）。 */
    static Instant parseUntil(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // 不是 ISO 时刻，走相对时长分支
        }
        Matcher m = RELATIVE_UNTIL.matcher(s);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            long minutes = switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'm' -> n;
                case 'h' -> n * 60L;
                case 'd' -> n * 24 * 60L;
                default -> 0L;
            };
            return Instant.now().plus(minutes, ChronoUnit.MINUTES);
        }
        return null;
    }
}
