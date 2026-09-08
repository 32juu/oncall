package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.util.List;

/**
 * 图片 → 文本（看图说话）：把上传图片交给多模态大模型（Qwen-VL 系，见 {@code VisionModelConfig}）
 * 产出<b>可检索的 Markdown 描述</b>，随后由 {@link VectorIndexService#indexParsedText} 走既有 RAG
 * 索引管道（delete→chunk→embed→insert）落 Milvus —— 本类是多模态切片的唯一新前置。
 *
 * <p>关键桥接面：Spring AI {@link UserMessage} 携带 {@link Media}，DashScopeChatModel 已把 UserMessage 的
 * media 转 {@code data:...;base64}（convertMediaContent），故无需 pom 依赖、无需原生 dashscope API。
 * 构造器注入接口型 {@link ChatModel}（@Qualifier("visionChatModel")），测试直接 mock、不实连。
 */
@Service
public class ImageCaptionService {

    private static final Logger logger = LoggerFactory.getLogger(ImageCaptionService.class);

    private final ChatModel visionModel;

    public ImageCaptionService(@Qualifier("visionChatModel") ChatModel visionModel) {
        this.visionModel = visionModel;
    }

    /**
     * 让视觉模型把图片读成 Markdown 文本。
     *
     * @param fileName   源文件名（写进标题/元信息，便于 RAG 分块与检索溯源）
     * @param imageBytes 图片字节（原样走 Media.data，框架转 base64）
     * @param mimeType   图片 MIME，如 image/png
     * @return 去首尾空白的 Markdown 解析文本
     */
    public String caption(String fileName, byte[] imageBytes, String mimeType) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("图片文件名不能为空");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }

        UserMessage userMessage = UserMessage.builder()
                .text(buildInstruction(fileName))
                .media(Media.builder()
                        .mimeType(MimeType.valueOf(mimeType))
                        .data(imageBytes)
                        .name(fileName)
                        .build())
                .build();
        Prompt prompt = new Prompt(List.of(userMessage));

        logger.info("调用视觉模型看图: {} ({} bytes, {})", fileName, imageBytes.length, mimeType);
        ChatResponse response = visionModel.call(prompt);

        String text = (response == null || response.getResult() == null
                || response.getResult().getOutput() == null)
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("视觉模型未返回任何解析文本: " + fileName);
        }
        return text.trim();
    }

    /**
     * 指令要求：纯 Markdown + 一级标题带源文件名，供 {@code DocumentChunkService} 按标题分块、
     * 内部文档检索可命中；段落化原样保留指标/告警名等关键信息。
     */
    private String buildInstruction(String fileName) {
        return "你是一名 SRE 运维助手，正在把一张图片/截图转为可供检索的 Markdown 文本（随后会进知识库被 RAG 检索）。"
                + "请用简体中文输出纯 Markdown：\n"
                + "1. 第一行写一级标题：# " + fileName + "（图片解析）\n"
                + "2. 用段落描述图中出现的所有文字、数字、指标名、告警名、图表/拓扑内容，尽量原样保留关键数值与标识；\n"
                + "3. 若图中有表格或列表，请用 Markdown 表格/列表还原；\n"
                + "4. 只输出解析文本本身，不要任何前言、解释、代码围栏或 Markdown 代码块标记。";
    }
}
