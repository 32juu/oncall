package org.example.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原生文档抽文本单测：测试内用 PDFBox/POI 现场造 pdf/docx/pptx（离线，不实连任何服务）。
 * 覆盖：三格式抽到关键文本、未知扩展名 → IAE、无文字层(PDF 空白页) → ISE、
 * normalize 折叠空行 + 超长单行断行（防 DocumentChunkService 单段超限整块入一个 chunk）。
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    // ==================== 三格式离线 fixture ====================

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text); // PDType1Font 只支持 Latin，fixture 用英文
                cs.endText();
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    private byte[] pdfBlank() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    private byte[] docxWithText(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.write(out);
                return out.toByteArray();
            }
        }
    }

    private byte[] pptxWithText(String text) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText(text);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ppt.write(out);
                return out.toByteArray();
            }
        }
    }

    // ==================== 用例 ====================

    @Test
    void pdf_textIsExtracted() throws Exception {
        String text = extractor.extract("r.pdf", "pdf", pdfWithText("CPU 85% HighCPUUsage"));
        assertTrue(text.contains("CPU 85%"), () -> "应含图内文本, 实得: " + text);
        assertTrue(text.contains("HighCPUUsage"));
    }

    @Test
    void docx_textIsExtracted() throws Exception {
        String text = extractor.extract("r.docx", "docx", docxWithText("JVM 堆 2GB 认领流程"));
        assertTrue(text.contains("JVM 堆 2GB"));
    }

    @Test
    void pptx_textIsExtracted() throws Exception {
        String text = extractor.extract("r.pptx", "pptx", pptxWithText("告警 HighCPUUsage 阈值 85%"));
        assertTrue(text.contains("HighCPUUsage"));
        assertTrue(text.contains("阈值 85%"));
    }

    @Test
    void unsupportedExtension_throwsIllegalArgumentException() throws Exception {
        byte[] pdf = pdfWithText("x");
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("a.xlsx", "xlsx", pdf));
    }

    @Test
    void pdfWithoutTextLayer_throwsIllegalStateException() throws Exception {
        // 扫描件/图片型 PDF（无文字层）→ 空文本 → ISE（语义同图片 caption 空，FileUpload 兜 500）
        assertThrows(IllegalStateException.class, () -> extractor.extract("scan.pdf", "pdf", pdfBlank()));
    }

    @Test
    void overlongLine_isWrappedUnderLineLimit() {
        // 无空格长行：硬切但内容不丢
        String longNoSpace = "A".repeat(5000);
        String wrapped = DocumentTextExtractor.wrapOverlongLine(longNoSpace);
        assertContentPreserved(longNoSpace, wrapped);
        assertMaxLineLength(wrapped);
        assertTrue(wrapped.split("\n", -1).length > 1, "5000 字单行应被断成多行");

        // 有空格长行：断点都在窗口内空格处，去换行后仍与原内容一致（不撕裂、不丢字）
        String words = repeatWords("high cpu usage warning alert", 300);
        String wrappedWords = DocumentTextExtractor.wrapOverlongLine(words);
        assertContentPreserved(words, wrappedWords);
        assertMaxLineLength(wrappedWords);
    }

    private void assertMaxLineLength(String text) {
        long maxLine = Arrays.stream(text.split("\n", -1))
                .mapToInt(String::length).max().orElse(Integer.MAX_VALUE);
        assertTrue(maxLine <= DocumentTextExtractor.MAX_LINE_LENGTH, () -> "断行后仍超限: " + maxLine);
    }

    private void assertContentPreserved(String original, String wrapped) {
        assertEquals(original, wrapped.replace("\n", ""), "断行只应插入换行，不应增删字符");
    }

    @Test
    void normalize_collapsesBlankRunsAndTrims() {
        assertEquals("a\n\nb\n\nc", DocumentTextExtractor.normalize("  a\n\n\n\n\nb\r\n\r\nc  "));
        assertEquals("", DocumentTextExtractor.normalize(null));
        assertEquals("", DocumentTextExtractor.normalize("   \n  "));
    }

    private static String repeatWords(String phrase, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(phrase).append(' ');
        }
        return sb.toString();
    }
}
