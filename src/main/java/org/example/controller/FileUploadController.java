package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.service.DocumentTextExtractor;
import org.example.service.ImageCaptionService;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private ImageCaptionService imageCaptionService;

    @Autowired
    private DocumentTextExtractor documentTextExtractor;

    /** 多模态白名单：/api/upload 收这些扩展名的图片 → Qwen-VL 看图出 Markdown → 入 RAG（application.yml 同步放行） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** 原生文档白名单：pdf/docx/pptx → DocumentTextExtractor 抽文本 → 走与图片同一 indexParsedText 缝入 RAG（specs/004） */
    private static final Set<String> DOC_EXTENSIONS = Set.of("pdf", "docx", "pptx");

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 使用原始文件名，而不是UUID，以便实现基于文件名的去重
            Path filePath = uploadDir.resolve(originalFilename).normalize();
            
            // 如果文件已存在，先删除旧文件（实现覆盖更新）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }
            
            Files.copy(file.getInputStream(), filePath);

            logger.info("文件上传成功: {}", filePath);

            // 文件上传成功后，自动建索引：图片/文档 = 解析出纯文本走 indexParsedText；txt/md = 原 indexSingleFile（语义不变）
            if (isImageExtension(fileExtension)) {
                // 图片若解析/入库失败就没有任何内容可进库，假 200 会误导演示 → 诚实报 500（文件已落盘保留，便于排查）
                try {
                    byte[] imageBytes = Files.readAllBytes(filePath);
                    String caption = imageCaptionService.caption(originalFilename, imageBytes, imageMimeType(fileExtension));
                    vectorIndexService.indexParsedText(filePath.toString(), caption);
                    logger.info("图片解析并索引成功: {}, 文本 {} 字符", filePath, caption.length());
                } catch (Exception e) {
                    logger.error("图片解析/入库失败: {}, 错误: {}", filePath, e.getMessage(), e);
                    ApiResponse<String> errorResponse = new ApiResponse<>();
                    errorResponse.setCode(500);
                    errorResponse.setMessage("图片解析或入库失败: " + e.getMessage() + "（文件已保存，未入库）");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(errorResponse);
                }
            } else if (isDocumentExtension(fileExtension)) {
                // 文档同图片哲学：抽不出文本（如扫描件 PDF 无文字层）即无可入库，假 200 会误导演示 → 诚实报 500
                try {
                    byte[] docBytes = Files.readAllBytes(filePath);
                    String text = documentTextExtractor.extract(originalFilename, fileExtension, docBytes);
                    vectorIndexService.indexParsedText(filePath.toString(), text);
                    logger.info("文档抽文本并索引成功: {}, 文本 {} 字符", filePath, text.length());
                } catch (Exception e) {
                    logger.error("文档解析/入库失败: {}, 错误: {}", filePath, e.getMessage(), e);
                    ApiResponse<String> errorResponse = new ApiResponse<>();
                    errorResponse.setCode(500);
                    errorResponse.setMessage("文档解析或入库失败: " + e.getMessage() + "（文件已保存，未入库）");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(errorResponse);
                }
            } else {
                try {
                    logger.info("开始为上传文件创建向量索引: {}", filePath);
                    vectorIndexService.indexSingleFile(filePath.toString());
                    logger.info("向量索引创建成功: {}", filePath);
                } catch (Exception e) {
                    logger.error("向量索引创建失败: {}, 错误: {}", filePath, e.getMessage(), e);
                    // 注意：即使索引失败，文件上传仍然成功（txt/md 文件本身仍是内容源），只记录错误日志
                    // 可以根据业务需求决定是否要删除文件或返回错误
                }
            }

            FileUploadRes response = new FileUploadRes(
                    originalFilename,
                    filePath.toString(),
                    file.getSize()
            );

            // 使用统一的API响应格式
            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            apiResponse.setData(response);
            
            return ResponseEntity.ok(apiResponse);

        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    /**
     * 统一 API 响应格式
     */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    private boolean isImageExtension(String extension) {
        return IMAGE_EXTENSIONS.contains(extension);
    }

    private boolean isDocumentExtension(String extension) {
        return DOC_EXTENSIONS.contains(extension);
    }

    private String imageMimeType(String extension) {
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            default:
                throw new IllegalArgumentException("不支持的图片扩展名: " + extension);
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
