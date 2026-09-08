package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.claim.tool.ClaimAlertTool;
import org.example.claim.tool.SuppressAlertTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    // 直贴图(形态 B)：用户输入 base64 解码后上限 4MB（防超大 body/内存）
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    // 与 /api/upload 图片白名单同构：jpg/jpeg → image/jpeg、png → image/png、webp → image/webp
    private static final Set<String> SUPPORTED_IMAGE_MIME = Set.of("image/jpeg", "image/png", "image/webp");

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private ImageCaptionService imageCaptionService; // 直贴图复用切片1 的 VL 看图服务（qwen-vl → Markdown）

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired(required = false)  // 认领写工具：agent.claim-tool-enabled=true 才注册（D3 默认拒绝）；只接聊天 agent（认领=人工接管，AIOps 不接）
    private ClaimAlertTool claimAlertTool;

    @Autowired(required = false)  // 抑制窗口写工具（suppressAlert/cancelSuppression）：与认领同闸、同 operator、同 chat-only 原则
    private SuppressAlertTool suppressAlertTool;

    @Autowired
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n");
        if (claimAlertTool != null) {
            // claim-tool-enabled=true：向模型显式路由认领动作（否则模型可能只调用提示词中点名的工具）
            systemPromptBuilder.append("当用户要认领（我来处理/接手）某条告警时，使用 claimAlert 工具，以本工位值班负责人名义认领，无需向用户索要负责人。\n");
        }
        if (suppressAlertTool != null) {
            // 抑制窗口路由：设窗/取消是同一个 property 闸，与 claimAlert 同时出现
            systemPromptBuilder.append("当用户要抑制某条告警（如「抑制 HighCPUUsage 2小时」「先静默一下」）时，使用 suppressAlert 工具；"
                    + "当用户要取消抑制、恢复某条告警的提醒时，使用 cancelSuppression 工具。"
                    + "until 给相对时长（如 2h、90m、1d）或具体 ISO 时刻即可。\n");
        }
        systemPromptBuilder.append("\n");
        
        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 固定工具：dateTime / internalDocs / queryMetrics；
     * 条件工具：queryLogsTools（cls.mock-enabled）、claimAlertTool 与 suppressAlertTool（agent.claim-tool-enabled）按需追加。
     */
    public Object[] buildMethodToolsArray() {
        List<Object> tools = new ArrayList<>(List.of(dateTimeTools, internalDocsTools, queryMetricsTools));
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            tools.add(queryLogsTools);
        }
        if (claimAlertTool != null) {
            // claim-tool-enabled=true 时包含认领工具（默认缺省关，行为与关闭前一致）
            tools.add(claimAlertTool);
        }
        if (suppressAlertTool != null) {
            // 与认领同一 property 闸：抑制工具随 claim 工具一起开关
            tools.add(suppressAlertTool);
        }
        return tools.toArray();
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")      // Agent标识名称
                .model(chatModel)                    // 绑定通义千问大模型，负责思考推理
                .systemPrompt(systemPrompt)           // 系统提示词，约束运维场景角色、ReAct思考规范
                .methodTools(buildMethodToolsArray())  // 注册工具描述（标准ToolJSON，知识库检索工具）
                .tools(getToolCallbacks())              // 工具实际执行回调，调用Milvus向量召回逻辑
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }

    /**
     * 直贴图(形态 B)的本轮用户文本解析：计算真正发给 agent 的 user turn。
     * <p>无图 → 原样返回 question（老路径零行为差）；有图 → 解码 base64 → qwen-vl 转述成 Markdown →
     * 组 {@code 【用户上传图片 …的转述】\n{caption}(\n\n【用户问题】{question})}——图片内容以文本进 agent，
     * agent 看完图仍能照常调 RAG/认领/抑制工具；caption 随 turn 进 history，跨轮可引用。
     * <p>非法输入（坏 base64 / 不支持 mime / 超大 / caption 失败）抛异常，由 controller 现有 catch 兜成聊天错误信封。
     */
    public String resolveUserTurn(String question, String imageBase64, String imageMimeType, String imageFileName) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return question;
        }
        if (imageMimeType == null || !SUPPORTED_IMAGE_MIME.contains(imageMimeType)) {
            throw new IllegalArgumentException("不支持的图片类型: " + imageMimeType + "（仅支持 jpg/jpeg/png/webp）");
        }
        byte[] imageBytes = decodeImageBase64(imageBase64);
        if (imageBytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片过大，上限 " + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB");
        }
        String fileName = (imageFileName == null || imageFileName.isBlank()) ? "粘贴图片" : imageFileName;

        // VL 转述：失败（网络/模型）异常直接上抛 → controller 包成聊天错误
        String caption = imageCaptionService.caption(fileName, imageBytes, imageMimeType);

        StringBuilder turn = new StringBuilder();
        turn.append("【用户上传图片 ").append(fileName).append(" 的转述内容】\n").append(caption);
        if (question != null && !question.isBlank()) {
            turn.append("\n\n【用户问题】").append(question.trim());
        }
        return turn.toString();
    }

    /**
     * 解码图片 base64：容忍 {@code data:image/png;base64,} 前缀（前端 FileReader 直出形态）。
     */
    private byte[] decodeImageBase64(String base64) {
        String s = base64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma != -1) {
            s = s.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片 base64 格式非法");
        }
    }
}
