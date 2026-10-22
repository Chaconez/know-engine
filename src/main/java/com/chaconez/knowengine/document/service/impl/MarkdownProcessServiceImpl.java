package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.constant.ContentType;
import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.constant.FileType;
import com.chaconez.knowengine.document.constant.KnowledgeBaseType;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.service.FileStorageService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档处理服务实现类
 * <p>
 * 与 MinerU 流程不同，此处的输入流本身就是一个 Markdown 文件，
 * 文件中的图片地址通常已经是公网可访问的 URL（如 MinerU 输出的 cdn 地址）。
 * 该类的职责：
 * 1. 直接读取 Markdown 内容
 * 2. 解析其中的图片标签 ![alt](url)
 * 3. 调用视觉大模型为每张图片生成描述，替换 alt 文本
 * 4. 将处理后的 Markdown 上传到 MinIO，并更新文档状态为 CONVERTED
 */
@Slf4j
@Service
public class MarkdownProcessServiceImpl extends MinerUProcessBaseServiceImpl {

    private static final String CONVERTED_FILE_DIR = "converted/";

    /**
     * 匹配 Markdown 图片标签：![alt](url)
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Override
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理 Markdown 文档图片描述生成，documentId: {}", document.getDocTitle());

        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);

        try {
            // 1. 读取 Markdown 文件内容
            String mdContent = readInputStreamAsString(inputStream);

            // 2. 为图片生成描述并替换 alt 文本
            String processedMdContent = enrichImageDescriptions(mdContent);

            // 3. 上传处理后的 Markdown 到 MinIO
            String docTitle = document.getDocTitle();
            String baseName = docTitle.contains(".") ? docTitle.substring(0, docTitle.lastIndexOf(".")) : docTitle;
            String convertedObjectName = CONVERTED_FILE_DIR + baseName + ".md";
            String convertedUrl = fileStorageService.uploadFile(
                    convertedObjectName,
                    processedMdContent.getBytes(StandardCharsets.UTF_8),
                    ContentType.TEXT_MARKDOWN);

            // 4. 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);

            log.info("Markdown 文档处理完成，documentId: {}, convertedUrl: {}", document.getDocTitle(), convertedUrl);
            return convertedUrl;
        } catch (Exception e) {
            log.error("Markdown 文档处理失败，documentId: {}", document.getDocTitle(), e);
            // 处理失败，状态回滚为 UPLOADED
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            Assert.isTrue(result, "文件UPLOADED状态更新失败");
            throw new RuntimeException("Markdown 文档处理失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
        }
    }

    /**
     * 遍历 Markdown 中的图片标签，为每张图片调用视觉模型生成描述并替换 alt 文本
     * 例如：![image](https://xxx/foo.jpg) -> ![这是一张xxx图片，xxx描述](https://xxx/foo.jpg)
     */
    private String enrichImageDescriptions(String mdContent) {
        Matcher matcher = IMAGE_PATTERN.matcher(mdContent);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String originAlt = matcher.group(1);
            String imageUrl = matcher.group(2);

            String description;
            try {
                // 调用基类提供的视觉模型生成描述
                description = generateImageDescription(imageUrl);
                if (description == null || description.isBlank()) {
                    log.warn("图片描述为空，保留原 alt 文本，url: {}", imageUrl);
                    description = originAlt;
                } else {
                    // 去除可能存在的换行，保证 Markdown 标签单行
                    description = description.replaceAll("[\\r\\n]+", " ").trim();
                }
                log.info("图片描述已生成: {} -> {}", imageUrl, description);
            } catch (Exception e) {
                log.warn("生成图片描述失败，保留原 alt 文本，url: {}", imageUrl, e);
                description = originAlt;
            }

            String newImageTag = "![" + description + "](" + imageUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 将 InputStream 全量读取为字符串
     */
    private String readInputStreamAsString(InputStream inputStream) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 安静关闭输入流，忽略异常
     */
    private void closeQuietly(InputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return fileType == FileType.MARKDOWN;
    }
}
