package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ChatRequest 传输契约锁：老报文（仅 Id/Question）不带新字段仍可解析（向后兼容，纯文本行为零差）；
 * 直贴图新字段三种 key 风格（驼峰 / snake / 前端冗余别名）都能到位。
 */
class ChatRequestParseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyPayload_withoutImageFields_stillParses() throws Exception {
        ChatController.ChatRequest request = objectMapper.readValue(
                "{\"Id\":\"s1\",\"Question\":\"你好\"}", ChatController.ChatRequest.class);

        assertEquals("s1", request.getId());
        assertEquals("你好", request.getQuestion());
        assertNull(request.getImageBase64());
        assertNull(request.getImageMimeType());
        assertNull(request.getImageFileName());
    }

    @Test
    void camelCaseImageFields_parse() throws Exception {
        ChatController.ChatRequest request = objectMapper.readValue(
                "{\"Id\":\"s1\",\"Question\":\"看图\",\"imageBase64\":\"aGk=\","
                        + "\"imageMimeType\":\"image/png\",\"imageFileName\":\"shot.png\"}",
                ChatController.ChatRequest.class);

        assertEquals("aGk=", request.getImageBase64());
        assertEquals("image/png", request.getImageMimeType());
        assertEquals("shot.png", request.getImageFileName());
    }

    @Test
    void snakeCaseAliases_parse() throws Exception {
        ChatController.ChatRequest request = objectMapper.readValue(
                "{\"image_base64\":\"aGk=\",\"image_mime_type\":\"image/jpeg\",\"image_file_name\":\"a.jpg\"}",
                ChatController.ChatRequest.class);

        assertEquals("aGk=", request.getImageBase64());
        assertEquals("image/jpeg", request.getImageMimeType());
        assertEquals("a.jpg", request.getImageFileName());
    }
}
