package org.example.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 直贴图(形态 B)的 user-turn 解析缝单测：resolveUserTurn = 解码 base64 → VL 转述 → 组 [转述+问题]。
 * ImageCaptionService 以 mock 隔离，绝不实连 DashScope；无图走老路径零行为差。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ImageCaptionService imageCaptionService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        ReflectionTestUtils.setField(service, "imageCaptionService", imageCaptionService);
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void noImage_returnsQuestionUnchangedAndNeverCaptions() {
        // null 字段与空字符串都视为「无图」→ 老路径，不触发任何 VL 调用
        assertEquals("你好", service.resolveUserTurn("你好", null, null, null));
        assertEquals("  ", service.resolveUserTurn("  ", "", "", ""));
        verify(imageCaptionService, never()).caption(anyString(), any(), anyString());
    }

    @Test
    void withImage_captionsAndComposesTurn() {
        byte[] img = {1, 2, 3};
        when(imageCaptionService.caption("shot.png", img, "image/png"))
                .thenReturn("# shot.png\nCPU 使用率 85%");

        String turn = service.resolveUserTurn("认领这个", b64(img), "image/png", "shot.png");

        // 组装结构：附图转述块在前、用户问题在后
        assertEquals("【用户上传图片 shot.png 的转述内容】\n# shot.png\nCPU 使用率 85%\n\n【用户问题】认领这个", turn);

        // 解码出的字节原样进 caption（桥接缝另一端 = ImageCaptionServiceTest 已锁）
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(imageCaptionService).caption(eq("shot.png"), captor.capture(), eq("image/png"));
        assertArrayEquals(img, captor.getValue());
    }

    @Test
    void imageWithoutQuestion_stillReturnsCaptionTurn() {
        byte[] img = {9};
        when(imageCaptionService.caption("粘贴图片", img, "image/png")).thenReturn("只有一张图");

        String turn = service.resolveUserTurn(null, b64(img), "image/png", null);

        assertTrue(turn.startsWith("【用户上传图片 粘贴图片 的转述内容】\n只有一张图"));
        assertTrue(!turn.contains("用户问题"));
    }

    @Test
    void dataUrlPrefix_isStrippedBeforeDecode() {
        byte[] img = {4, 5};
        when(imageCaptionService.caption("clip.png", img, "image/png")).thenReturn("ok");
        String dataUrl = "data:image/png;base64," + b64(img);

        service.resolveUserTurn("看图", dataUrl, "image/png", "clip.png");

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(imageCaptionService).caption(eq("clip.png"), captor.capture(), eq("image/png"));
        assertArrayEquals(img, captor.getValue());
    }

    @Test
    void malformedBase64_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveUserTurn("q", "!!!not-base64!!!", "image/png", "a.png"));
    }

    @Test
    void unsupportedMimeType_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveUserTurn("q", b64(new byte[]{1}), "image/gif", "a.gif"));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveUserTurn("q", b64(new byte[]{1}), null, "a.png"));
    }

    @Test
    void oversizedImage_throwsIllegalArgumentException() {
        byte[] big = new byte[4 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveUserTurn("q", b64(big), "image/png", "big.png"));
    }

    @Test
    void captionFailure_propagatesAsIs() {
        byte[] img = {7};
        when(imageCaptionService.caption("a.png", img, "image/png"))
                .thenThrow(new IllegalStateException("视觉模型不可用"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.resolveUserTurn("q", b64(img), "image/png", "a.png"));
        assertTrue(ex.getMessage().contains("视觉模型不可用"));
    }
}
