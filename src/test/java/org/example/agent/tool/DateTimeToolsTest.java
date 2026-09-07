package org.example.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DateTimeTools 输出契约测试。
 *
 * 探针结论（2026-09-07）：getCurrentDateTime() 依赖 LocaleContextHolder.getTimeZone()
 * （环境隐式上下文），曾推测无请求线程下会 NPE——实测绿：Spring 回落系统默认时区，独立线程可调用。
 * 🐛B 降级为设计臭味（对"时区从哪来"零声明），非崩溃缺陷。
 *
 * 这里钉的是工具输出的【形状契约】：ISO 8601 本地日期时间（T 分隔、可机解析），且日期为今天——
 * 下游/LLM 依赖稳定形状，而非某个具体时刻（时间不可注入，无法断言精确值）。
 */
class DateTimeToolsTest {

    private final DateTimeTools tools = new DateTimeTools();

    @Test
    void getCurrentDateTime_canBeInvoked_inPlainThread() {
        // 探针：无 web 请求、无 LocaleContext 绑定的普通线程也必须能调用（探针已证非 NPE）
        assertThat(tools.getCurrentDateTime()).isNotBlank();
    }

    @Test
    void getCurrentDateTime_isIso8601LocalDateTime_ofToday() {
        String today = LocalDate.now().toString(); // 调用前取，最小化跨午夜的（理论）竞态
        String result = tools.getCurrentDateTime();

        assertThat(result).startsWith(today);
        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }
}
