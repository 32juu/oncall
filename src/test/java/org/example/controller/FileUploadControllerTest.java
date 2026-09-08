package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.service.DocumentTextExtractor;
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
 * HTTP 层契约：/api/upload 按扩展名三分支——图片走 caption→indexParsedText、文档(pdf/docx/pptx)走 extract→indexParsedText
 * （解析失败均诚实 500，无可入库即不假 200）；txt/md 保持原 indexSingleFile（索引失败仍 200）。controller 真落盘到 @TempDir
 * （FileUploadConfig mock），VectorIndexService / ImageCaptionService / DocumentTextExtractor 全 mock，无实连。
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
    private DocumentTextExtractor documentTextExtractor;

    @MockBean
    private FileUploadConfig fileUploadConfig;

    @TempDir
    Path uploadDir;

    private void allowImages() {
        when(fileUploadConfig.getAllowedExtensions())
                .thenReturn("txt,md,jpg,jpeg,png,webp,pdf,docx,pptx");
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
    void pdfUpload_extractsThenIndexParsedText() throws Exception {
        allowImages();
        when(documentTextExtractor.extract(eq("runbook.pdf"), eq("pdf"), any()))
                .thenReturn("告警认领手册：谁认领谁负责\n一页讲清状态机");

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "runbook.pdf", "application/pdf", "PDF".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileName").value("runbook.pdf"));

        verify(documentTextExtractor).extract(eq("runbook.pdf"), eq("pdf"), any());
        // sourceId = 存储路径（含原文件名）
        verify(vectorIndexService).indexParsedText(contains("runbook.pdf"), eq("告警认领手册：谁认领谁负责\n一页讲清状态机"));
        verify(vectorIndexService, never()).indexSingleFile(anyString());
    }

    @Test
    void docxUpload_extractsThenIndexParsedText() throws Exception {
        allowImages();
        when(documentTextExtractor.extract(eq("ops.docx"), eq("docx"), any()))
                .thenReturn("OnCall 值班规范：告警 5 分钟内必须有人认领");

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "ops.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "DOCX".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(documentTextExtractor).extract(eq("ops.docx"), eq("docx"), any());
        verify(vectorIndexService).indexParsedText(contains("ops.docx"), eq("OnCall 值班规范：告警 5 分钟内必须有人认领"));
        verify(imageCaptionService, never()).caption(any(), any(), any());
    }

    @Test
    void documentUpload_extractFailure_returns500AndSkipsIndex() throws Exception {
        allowImages();
        // 扫描件/图片型 PDF：无文字层 → extractor 抛 ISE → 无可入库内容 → 诚实 500（同图片 caption 失败哲学）
        when(documentTextExtractor.extract(eq("scan.pdf"), eq("pdf"), any()))
                .thenThrow(new IllegalStateException("可能是扫描件/图片型 PDF，无文字层"));

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", "PDF".getBytes())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));

        verify(vectorIndexService, never()).indexParsedText(anyString(), anyString());
    }

    @Test
    void disallowedExtension_returns400() throws Exception {
        allowImages();

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "notes.exe", "application/octet-stream", "x".getBytes())))
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
