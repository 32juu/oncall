package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.service.ImageCaptionService;
import org.example.service.VectorIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层契约：/api/upload 按扩展名分流——图片走 caption→indexParsedText（失败诚实 500），
 * txt/md 保持原 indexSingleFile（索引失败仍 200）。controller 真落盘到 @TempDir（FileUploadConfig mock），
 * VectorIndexService / ImageCaptionService 全 mock，无实连。
 */
@WebMvcTest(FileUploadController.class)
class FileUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VectorIndexService vectorIndexService;

    @MockBean
    private ImageCaptionService imageCaptionService;

    @MockBean
    private FileUploadConfig fileUploadConfig;

    @TempDir
    Path uploadDir;

    private void allowImages() {
        when(fileUploadConfig.getAllowedExtensions()).thenReturn("txt,md,jpg,jpeg,png,webp");
        when(fileUploadConfig.getPath()).thenReturn(uploadDir.toString());
    }

    @Test
    void imageUpload_routesCaptionThenIndexParsedText() throws Exception {
        allowImages();
        when(imageCaptionService.caption(eq("shot.png"), any(), eq("image/png")))
                .thenReturn("# shot.png（图片解析）\nCPU 使用率 85%");

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "shot.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileName").value("shot.png"));

        verify(imageCaptionService).caption(eq("shot.png"), any(), eq("image/png"));
        // sourceId = 存储路径（含原文件名）
        verify(vectorIndexService).indexParsedText(contains("shot.png"), eq("# shot.png（图片解析）\nCPU 使用率 85%"));
        verify(vectorIndexService, never()).indexSingleFile(anyString());
    }

    @Test
    void jpgUpload_mapsJpgToImageJpegMime() throws Exception {
        allowImages();
        when(imageCaptionService.caption(eq("a.jpg"), any(), eq("image/jpeg")))
                .thenReturn("# a.jpg\nJVM 堆 2GB");

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{9})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(imageCaptionService).caption(eq("a.jpg"), any(), eq("image/jpeg"));
        verify(vectorIndexService).indexParsedText(contains("a.jpg"), eq("# a.jpg\nJVM 堆 2GB"));
    }

    @Test
    void textUpload_keepsOriginalIndexSingleFilePath() throws Exception {
        allowImages();

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown",
                                "# 手册\n认领流程".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(vectorIndexService).indexSingleFile(contains("runbook.md"));
        verify(imageCaptionService, never()).caption(any(), any(), any());
    }

    @Test
    void disallowedExtension_returns400() throws Exception {
        allowImages();

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "notes.pdf", "application/pdf", "x".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyFile_returns400() throws Exception {
        allowImages();

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imageUpload_captionFailure_returns500AndSkipsIndex() throws Exception {
        allowImages();
        when(imageCaptionService.caption(eq("bad.png"), any(), any()))
                .thenThrow(new RuntimeException("vision 服务不可用"));

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "bad.png", "image/png", new byte[]{5})))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));

        verify(vectorIndexService, never()).indexParsedText(anyString(), anyString());
    }
}
