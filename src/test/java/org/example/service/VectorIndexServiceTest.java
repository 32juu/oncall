package org.example.service;

import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * indexParsedText 缝单测：spy 掉 deleteExistingData / insertToMilvus（不触碰真 Milvus，不造 R<> 实例），
 * 验证「delete → chunkDocument(sourceId) → embed → insert」编排正确、sourceId 一路贯通；indexSingleFile
 * 读文件后复走同缝。embedding/chunkService 全 mock，无实连。
 */
@ExtendWith(MockitoExtension.class)
class VectorIndexServiceTest {

    @Mock
    private DocumentChunkService chunkService;

    @Mock
    private VectorEmbeddingService embeddingService;

    private VectorIndexService service;

    @BeforeEach
    void setUp() {
        service = spy(new VectorIndexService());
        ReflectionTestUtils.setField(service, "chunkService", chunkService);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
    }

    private DocumentChunk chunk(String content) {
        return new DocumentChunk(content, 0, content.length(), 0);
    }

    @Test
    void indexParsedText_deletesThenChunksThenEmbedsAndInsertsOnce() throws Exception {
        String source = "uploads/shot.png";
        String text = "# shot.png（图片解析）\nCPU 使用率 85%";
        doNothing().when(service).deleteExistingData(source);
        doNothing().when(service).insertToMilvus(anyString(), anyList(), anyMap(), anyInt());
        when(chunkService.chunkDocument(text, source)).thenReturn(List.of(chunk(text)));
        when(embeddingService.generateEmbedding(text)).thenReturn(List.of(0.1f, 0.2f));

        service.indexParsedText(source, text);

        verify(service).deleteExistingData(source);       // 1. 按 sourceId 清旧
        verify(chunkService).chunkDocument(text, source); // 2. 同一 sourceId 分块
        verify(embeddingService).generateEmbedding(text); // 3. 逐块 embed
        verify(service).insertToMilvus(anyString(), anyList(), anyMap(), anyInt()); // 4. 逐块 insert
    }

    @Test
    void indexParsedText_emptyText_deletesButIndexesNothing() throws Exception {
        String source = "uploads/empty.jpg";
        doNothing().when(service).deleteExistingData(source);
        when(chunkService.chunkDocument("", source)).thenReturn(List.of());

        service.indexParsedText(source, "");

        verify(service).deleteExistingData(source);
        verify(embeddingService, never()).generateEmbedding(anyString());
        verify(service, never()).insertToMilvus(anyString(), anyList(), anyMap(), anyInt());
    }

    @Test
    void indexSingleFile_readsFileAndRoutesThroughSameSeam(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("runbook.md");
        String content = "# 告警处置手册\n第一步：认领";
        Files.writeString(file, content);
        doNothing().when(service).deleteExistingData(anyString());
        doNothing().when(service).insertToMilvus(anyString(), anyList(), anyMap(), anyInt());
        when(chunkService.chunkDocument(content, file.toString())).thenReturn(List.of(chunk(content)));
        when(embeddingService.generateEmbedding(content)).thenReturn(List.of());

        service.indexSingleFile(file.toString());

        verify(service).deleteExistingData(file.toString());
        verify(chunkService).chunkDocument(content, file.toString());
        verify(embeddingService).generateEmbedding(content);
        verify(service).insertToMilvus(anyString(), anyList(), anyMap(), anyInt());
    }
}
