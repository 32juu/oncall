package org.example.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 原生文档 → 可检索纯文本（语料扩宽收编，specs/004）：pdf(PDFBox) / docx / pptx(POI)。
 * <p>输出交给 {@link DocumentChunkService} 分块前做 {@link #normalize}：单行超长会在空白处断行，
 * 因为 chunkSection 对「单段落超 max-size」不硬切——不留规范化会整块塞进一个 chunk（远超 800 字上限）。
 * <p>纯文本抽取、不实连任何外部服务（本地解析可离线单测）；无文字层(如扫描件 PDF)会得到空文本 → 抛 {@link IllegalStateException}，
 * 语义与图片「无解析即无可入库内容」一致（FileUploadController 兜 500）。
 */
@Service
public class DocumentTextExtractor {

    /** 单行上限：超过则就近断行，保证任何单段落 ≤ 该值，杜绝超长单段整块入一个 chunk */
    static final int MAX_LINE_LENGTH = 600;

    /**
     * 抽取文档文本（去头尾空白、折叠空行、断超长行）。
     *
     * @param fileName 原始文件名（仅用于异常文案）
     * @param ext      小写扩展名：pdf | docx | pptx
     * @param bytes    文档字节
     * @return 可检索纯文本（非空）
     * @throws IllegalStateException    解析不出任何文本（无文字层 / 空文档）
     * @throws IllegalArgumentException 不支持的扩展名
     */
    public String extract(String fileName, String ext, byte[] bytes) {
        String raw;
        try {
            raw = switch (ext) {
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "pptx" -> extractPptx(bytes);
                default -> throw new IllegalArgumentException("不支持的文档扩展名: " + ext + "（仅支持 pdf/docx/pptx）");
            };
        } catch (IllegalArgumentException e) {
            throw e; // 上面 default 已转
        } catch (Exception e) {
            throw new IllegalStateException("文档解析失败: " + fileName + "（" + e.getMessage() + "）", e);
        }
        String text = normalize(raw);
        if (text.isEmpty()) {
            throw new IllegalStateException("未从文档中解析出任何文本: " + fileName
                    + "（可能是扫描件/图片型 PDF，无文字层）");
        }
        return text;
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (XWPFWordExtractor extractor = new XWPFWordExtractor(
                new XWPFDocument(new ByteArrayInputStream(bytes)))) {
            return extractor.getText();
        }
    }

    private String extractPptx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t).append('\n');
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 规范化抽取文本：统一换行符、折叠 3+ 空行 → 2、单行超长在空白处断行、去头尾空白。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.replace("\r\n", "\n").replace('\r', '\n');
        collapsed = collapsed.replaceAll("\n{3,}", "\n\n");

        String[] lines = collapsed.split("\n", -1);
        StringBuilder out = new StringBuilder(collapsed.length() + 16);
        for (String line : lines) {
            if (line.length() <= MAX_LINE_LENGTH) {
                out.append(line).append('\n');
            } else {
                out.append(wrapOverlongLine(line)).append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * 把单个超长行断成 ≤ {@link #MAX_LINE_LENGTH} 的行：优先在窗口内最后一个空格处断（整词），无空格则硬切。
     * <p>关键不变量：断行只插入换行、不增删任何字符——把输出里的换行去掉必须能还原原行。
     * 因此断点选在「空格之后」：空格随本行走（行尾留一个空格），下一行从空格后一个字符起，
     * 词间空格不丢。若像常见 wrap 那样用换行「吃掉」断点空格，检索文本里 "cpu usage" 会变 "cpuusage"，
     * 词被粘在一起、query token 无法命中（embedding 侧实害，非纯审美）。
     * <p>包私有：直接单测断行行为。
     */
    static String wrapOverlongLine(String line) {
        int n = line.length();
        StringBuilder sb = new StringBuilder(n + n / MAX_LINE_LENGTH);
        int start = 0;
        while (start < n) {
            int end = Math.min(start + MAX_LINE_LENGTH, n); // 本窗口独占上界
            if (end == n) {
                sb.append(line, start, n); // 剩余可整段放行
                break;
            }
            // 窗口内还有装不下的内容 → 找断点：窗口内最后一个空格（索引 ≤ end-1，行含空格 ≤ MAX 才成立）
            int space = line.lastIndexOf(' ', end - 1);
            if (space > start) {
                sb.append(line, start, space + 1); // 空格随本行走（含空格），零删字
                sb.append('\n');
                start = space + 1;
            } else {
                sb.append(line, start, end); // 窗口内无空格（或空格恰在行首）→ 硬切
                sb.append('\n');
                start = end;
            }
        }
        return sb.toString();
    }
}
