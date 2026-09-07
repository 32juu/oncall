package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock feed 形状契约锚（宪法 V：mock 只用于带显式开关的测试场景）。
 *
 * 为什么值得测：AlertDiagnosisRecorderTest 用手抄 JSON stub QueryMetricsTools。
 * 本测试钉住 queryPrometheusAlerts() 在 mock 模式的【真实输出形状】——若 SimplifiedAlert
 * 的 snake_case 映射或 mock 告警集漂移，这里先红，而不是让 claim 切片在生产静默断掉、
 * recorder 测试却因手抄 stub 照常全绿（测试在骗你）。
 */
class QueryMetricsToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private QueryMetricsTools mockModeTool() {
        QueryMetricsTools tool = new QueryMetricsTools();
        // mockEnabled 是 @Value 私有字段，直建对象无 Spring 注入；翻开关用反射
        // （testability 臭味：未来 ToolRegistry 应改构造注入，见执行文档 §5.2 草稿）
        ReflectionTestUtils.setField(tool, "mockEnabled", true);
        return tool;
    }

    @Test
    void queryPrometheusAlerts_mockMode_returnsParseableSuccessJson() throws Exception {
        String result = mockModeTool().queryPrometheusAlerts();

        JsonNode root = objectMapper.readTree(result);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("alerts").isArray()).isTrue();
        assertThat(root.path("message").asText()).isNotBlank();
    }

    @Test
    void queryPrometheusAlerts_mockMode_hasExactlyThreeKnownAlerts() throws Exception {
        JsonNode alerts = objectMapper.readTree(mockModeTool().queryPrometheusAlerts()).path("alerts");

        // 类 javadoc（L124-130）宣称 5 类（含 HighDiskUsage/ServiceUnavailable），代码实际只造 3 类
        // ——本测试钉代码真值，顺带证伪 🐛A
        assertThat(alerts.size()).isEqualTo(3);
        List<String> names = new ArrayList<>();
        alerts.forEach(n -> names.add(n.path("alert_name").asText()));
        assertThat(names).containsExactlyInAnyOrder("HighCPUUsage", "HighMemoryUsage", "SlowResponse");
    }

    @Test
    void queryPrometheusAlerts_mockMode_usesSnakeCaseAlertNameFiringState() throws Exception {
        JsonNode alerts = objectMapper.readTree(mockModeTool().queryPrometheusAlerts()).path("alerts");

        for (JsonNode alert : alerts) {
            assertThat(alert.has("alert_name")).isTrue();     // recorder L67 path("alert_name") 的命脉
            assertThat(alert.has("alertName")).isFalse();     // 防 camelCase 漂移
            assertThat(alert.path("alert_name").asText()).isNotBlank();
            assertThat(alert.path("state").asText()).isEqualTo("firing");
            assertThat(alert.path("description").asText()).isNotBlank();
        }
    }
}
