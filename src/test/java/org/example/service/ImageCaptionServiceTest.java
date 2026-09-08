package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多模态桥接缝单测：锁死「图片字节 + 指令 → 单个带 Media 的 UserMessage → ChatModel」这条输入端，
 * ChatModel 以 mock 隔离，绝不实连 DashScope。DashScope 侧把 UserMessage.media 转 data:base64 是框架行为，
 * 已 jar 实证，不在本测试范围。
 */
@ExtendWith(MockitoExtension.class)
class ImageCaptionServiceTest {

    @Mock
    private ChatModel visionModel;

    private ImageCaptionService service() {
        return new ImageCaptionService(visionModel);
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void caption_sendsSingleUserMessageWithMediaAndReturnsTrimmedText() {
        byte[] image = new byte[]{1, 2, 3, 4};
        when(visionModel.call(any(Prompt.class)))
                .thenReturn(textResponse("  # shot.png（图片解析）\nCPU 使用率 85%  "));

        String caption = service().caption("shot.png", image, "image/png");

        assertEquals("# shot.png（图片解析）\nCPU 使用率 85%", caption);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(visionModel).call(captor.capture());
        Prompt prompt = captor.getValue();

        // 恰一个 UserMessage
        assertEquals(1, prompt.getInstructions().size());
        assertInstanceOf(UserMessage.class, prompt.getInstructions().get(0));
        UserMessage user = (UserMessage) prompt.getInstructions().get(0);

        // 指令文本带源文件名（供 RAG 标题/溯源）与入库说明
        assertTrue(user.getText().contains("# shot.png"));
        assertTrue(user.getText().contains("RAG"));

        // 恰一张图片 Media：mime + 字节原样进框架桥接缝
        List<Media> media = user.getMedia();
        assertEquals(1, media.size());
        assertEquals(MimeType.valueOf("image/png"), media.get(0).getMimeType());
        assertArrayEquals(image, media.get(0).getDataAsByteArray());
    }

    @Test
    void caption_blankModelOutput_throws() {
        when(visionModel.call(any(Prompt.class))).thenReturn(textResponse("   "));
        assertThrows(IllegalStateException.class,
                () -> service().caption("shot.png", new byte[]{1}, "image/png"));
    }

    @Test
    void caption_nullResponse_throws() {
        when(visionModel.call(any(Prompt.class))).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> service().caption("shot.png", new byte[]{1}, "image/png"));
    }

    @Test
    void caption_blankFileNameOrEmptyBytes_rejectedBeforeModelCall() {
        assertThrows(IllegalArgumentException.class,
                () -> service().caption("  ", new byte[]{1}, "image/png"));
        assertThrows(IllegalArgumentException.class,
                () -> service().caption("shot.png", new byte[0], "image/png"));
    }
}
