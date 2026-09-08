package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉模型 bean（多模态，Qwen-VL 系）：构造方式镜像 {@code ChatService.createChatModel}（ChatService.java:62-84），
 * 建模不连网（DashScopeApi 是惰性 client），api-key 有默认值 → 不破坏无 key 启动。
 *
 * <p>暴露<b>接口型</b> {@link ChatModel}（而非具体 DashScopeChatModel）供 ImageCaptionService 构造器注入：
 * 测试可直接 mock，不实连 DashScope。模型可用 application.yml 的 {@code spring.ai.dashscope.vision.model} 切换
 * （qwen-vl-max 通用 / qwen-vl-ocr 文档扫描更强），不做枚举硬绑。
 */
@Configuration
public class VisionModelConfig {

    @Bean("visionChatModel")
    public ChatModel visionChatModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.vision.model:qwen-vl-max}") String model) {
        return DashScopeChatModel.builder()
                .dashScopeApi(DashScopeApi.builder().apiKey(apiKey).build())
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(model)
                        .withTemperature(0.2)
                        .withMaxToken(1500)
                        .build())
                .build();
    }
}
