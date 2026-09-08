package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolRegistry 单测：过滤语义（READ_ONLY 槽位丢弃写/高危）+ 未知名缺省 READ_ONLY + ApplicationContextRunner 验证 @ToolLevel 扫描。
 * 不依赖 DashScope / MySQL / 真实 agent。
 */
class ToolRegistryTest {

    private ToolCallback callbackNamed(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }

    // ============ 过滤语义：READ_ONLY 槽位 ============

    @Test
    void readOnlyOnly_keepsReadTools_dropsWriteAndHighRisk() {
        ToolRegistry registry = ToolRegistry.from(Map.of(
                "claimAlert", ToolLevel.Level.WRITE,
                "suppressAlert", ToolLevel.Level.WRITE,
                "cancelSuppression", ToolLevel.Level.WRITE,
                "restartService", ToolLevel.Level.HIGH_RISK));
        ToolCallback[] all = {
                callbackNamed("getCurrentDateTime"),   // 未登记 → READ_ONLY，保留
                callbackNamed("queryPrometheusAlerts"),// 未登记 → READ_ONLY，保留
                callbackNamed("claimAlert"),           // WRITE，滤除
                callbackNamed("restartService")        // HIGH_RISK，滤除
        };

        ToolCallback[] kept = registry.readOnlyOnly(all);

        assertThat(kept).hasSize(2);
        assertThat(kept).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("getCurrentDateTime", "queryPrometheusAlerts");
    }

    @Test
    void filter_allowedWrite_keepsWriteAndRead_butDropsHighRisk() {
        ToolRegistry registry = ToolRegistry.from(Map.of(
                "claimAlert", ToolLevel.Level.WRITE,
                "restartService", ToolLevel.Level.HIGH_RISK));
        ToolCallback[] all = {
                callbackNamed("getCurrentDateTime"),
                callbackNamed("claimAlert"),
                callbackNamed("restartService")
        };

        // 聊天槽位 = 允许到 WRITE：保留读 + 写，仍滤 HIGH_RISK（自愈留给更高授权，D2）
        ToolCallback[] kept = registry.filter(all, ToolLevel.Level.WRITE);

        assertThat(kept).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("getCurrentDateTime", "claimAlert");
    }

    @Test
    void filter_nullOrEmpty_input_survives() {
        ToolRegistry registry = ToolRegistry.from(Map.of("claimAlert", ToolLevel.Level.WRITE));

        assertThat(registry.readOnlyOnly(null)).isNull();
        assertThat(registry.readOnlyOnly(new ToolCallback[0])).isEmpty();
    }

    @Test
    void levelOf_unknownName_defaultsReadOnly() {
        ToolRegistry registry = ToolRegistry.from(Map.of("claimAlert", ToolLevel.Level.WRITE));

        assertThat(registry.levelOf("claimAlert")).isEqualTo(ToolLevel.Level.WRITE);
        assertThat(registry.levelOf("whateverMcpTool")).isEqualTo(ToolLevel.Level.READ_ONLY);
    }

    // ============ ApplicationContextRunner：扫描 @ToolLevel bean ============

    // 模拟一个声明了 WRITE 的写工具 + 一个未声明的读工具 bean，验证 scan 把类级分级映射到每个 @Tool 方法名
    @ToolLevel(ToolLevel.Level.WRITE)
    static class WriteFakeTool {
        @org.springframework.ai.tool.annotation.Tool(description = "write op")
        public String writeOp(String x) {
            return x;
        }
    }

    static class ReadFakeTool {
        @org.springframework.ai.tool.annotation.Tool(description = "read op")
        public String readOp(String x) {
            return x;
        }
    }

    @Test
    void scan_wireAnnotation_intoRegistry_andFilter() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(FakeToolConfig.class);
        runner.run(ctx -> {
            ToolRegistry registry = new ToolRegistry(ctx);

            // 只有显式 @ToolLevel 的 bean 被登记；未标注的读工具不登记（levelOf 时缺省 READ_ONLY）
            assertThat(registry.levelOf("writeOp")).isEqualTo(ToolLevel.Level.WRITE);
            assertThat(registry.levelOf("readOp")).isEqualTo(ToolLevel.Level.READ_ONLY);

            // READ_ONLY 槽位滤掉 writeOp
            ToolCallback write = callbackNamed("writeOp");
            ToolCallback read = callbackNamed("readOp");
            ToolCallback[] kept = registry.readOnlyOnly(new ToolCallback[]{write, read});
            assertThat(kept).hasSize(1);
            assertThat(kept[0].getToolDefinition().name()).isEqualTo("readOp");
        });
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Import({WriteFakeTool.class, ReadFakeTool.class})
    static class FakeToolConfig {}
}
