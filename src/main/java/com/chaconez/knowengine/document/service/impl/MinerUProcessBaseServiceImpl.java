package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.constant.ContentType;
import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.service.FileProcessService;
import com.chaconez.knowengine.document.service.FileStorageService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentService;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.chaconez.knowengine.document.constant.ContentType.ZIP;

/**
 * 文件处理服务 - 负责文档转换处理
 */
@Slf4j
public abstract class MinerUProcessBaseServiceImpl implements FileProcessService {

    private static final String CONVERTED_FILE_DIR = "converted/";

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Value("${file.parse.api.url:http://localhost:8000}")
    private String fileParseApiUrl;

    @Value("${file.parse.api.connectTimeout:30000}")
    private int connectTimeout;

    @Value("${file.parse.api.responseTimeout:300000}")
    private int responseTimeout;

    /**
     * 处理文档转换 - Markdown 格式
     * 1. 从 MinIO 下载文件
     * 2. 调用文档解析接口获取md/zip
     * 3. 转换后的文档保存在minio上
     * 3. 更新文档状态和转换后的 URL
     *
     * @param document 文档对象
     */
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        return processDocumentToMarkdownFromZip(document, inputStream);
    }

    /**
     * 处理文档转换为 Markdown 格式
     *
     * @param document 文档对象
     */
    public String processDocumentToMarkdown(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理文档转换为 Markdown，documentId: {}", document.getDocTitle());

        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);

        try {
            // 生成一串数字，避免文件名的中文乱码
            String docTitle = document.getDocTitle() + document.getDocTitle().hashCode();

            // 调用文档解析获取 Markdown
            String parseResult = parseDocumentToMarkdown(docTitle, inputStream);

            String markdownContent = JSON.parseObject(parseResult).getJSONObject("results").getJSONObject(docTitle).getString("md_content");
            // 保存转换后的内容到 MinIO
            String convertedObjectName = CONVERTED_FILE_DIR + document.getDocTitle().substring(0, document.getDocTitle().lastIndexOf(".")) + ".md";
            String convertedUrl = fileStorageService.uploadFile(convertedObjectName, markdownContent.getBytes(), ContentType.TEXT_MARKDOWN);

            // 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);
            log.info("文档 Markdown 转换完成，documentId: {}", document.getDocTitle());
            return convertedUrl;
        } catch (Exception e) {
            log.error("文档 Markdown 转换失败，documentId: {}", document.getDocTitle(), e);
            // 转换失败，状态回滚为 UPLOADED
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            Assert.isTrue(result, "文件UPLOADED状态更新失败");
            throw new RuntimeException("文档 Markdown 转换失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
        }
    }


    /**
     * 处理文档转换为 ZIP 格式
     * 1. 从 MinIO 下载文件
     * 2. 调用文档解析接口获取 ZIP（包含 Markdown 和图片）
     * 3. 更新文档状态和转换后的 URL
     *
     * @param document 文档对象
     */
    public String processDocumentToZip(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理文档转换为 ZIP，documentId: {}", document.getDocTitle());

        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);

        try {
            // 生成一串数字，避免文件名的中文乱码
            String docTitle = document.getDocTitle() + document.getDocTitle().hashCode();

            // 调用文档解析获取 ZIP 格式响应
            byte[] zipBytes = parseDocumentToZip(docTitle, inputStream);

            // 保存转换后的 ZIP 到 MinIO
            String convertedObjectName = CONVERTED_FILE_DIR + document.getDocTitle().substring(0, document.getDocTitle().lastIndexOf(".")) + ".zip";
            String convertedUrl = fileStorageService.uploadFile(convertedObjectName, zipBytes, ZIP);

            // 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);

            log.info("文档 ZIP 转换完成，documentId: {}", document.getDocTitle());
            return convertedUrl;
        } catch (Exception e) {
            log.error("文档 ZIP 转换失败，documentId: {}", document.getDocTitle(), e);
            // 转换失败，状态回滚为 UPLOADED
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            Assert.isTrue(result, "文件UPLOADED状态更新失败");
            throw new RuntimeException("文档 ZIP 转换失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
        }
    }

    /**
     * 处理文档转换为 ZIP 格式
     * 1. 调用文档解析接口获取 ZIP（包含 Markdown 和图片）
     * 2. 保存 ZIP 到本地磁盘
     * 3. 解压 ZIP 文件
     * 4. 上传解压后的 md 和图片到 MinIO
     * 5. 替换 md 中的图片地址为 MinIO 地址
     * 6. 调用 LLM 生成图片描述并更新 md
     * 7. 保存 md 的 MinIO 地址到 convertedUrl
     * 8. 异步清理本地临时文件
     *
     * @param document 文档对象
     */
    public String processDocumentToMarkdownFromZip(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理文档转换为 ZIP，documentId: {}", document.getDocTitle());

        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);

        String zipFilePath = null;
        String extractDir = null;

        try {
            // 生成一串数字，避免文件名的中文乱码
            String docTitle = document.getDocTitle() + document.getDocTitle().hashCode();

            // 1. 调用文档解析获取 ZIP 格式响应
            byte[] zipBytes = parseDocumentToZip(docTitle, inputStream);

            // 2. 保存 ZIP 到本地临时目录
            String tempDir = System.getProperty("java.io.tmpdir");
            String uniqueId = UUID.randomUUID().toString();
            zipFilePath = tempDir + File.separator + uniqueId + ".zip";
            extractDir = tempDir + File.separator + uniqueId + "_extracted";

            Files.write(Paths.get(zipFilePath), zipBytes);
            log.info("ZIP 文件已保存到本地: {}", zipFilePath);

            // 3. 解压 ZIP 文件
            extractZip(zipFilePath, extractDir);
            log.info("ZIP 文件已解压到: {}", extractDir);

            // 4. 上传解压后的 md 和图片到 MinIO，并处理 md 内容
            String mdMinioUrl = processExtractedFiles(document, extractDir);

            // 5. 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);

            log.info("文档 ZIP 转换完成，documentId: {}, mdUrl: {}", document.getDocTitle(), mdMinioUrl);
            return mdMinioUrl;
        } catch (Exception e) {
            log.error("文档 ZIP 转换失败，documentId: {}", document.getDocTitle(), e);
            // 转换失败，状态回滚为 UPLOADED
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            Assert.isTrue(result, "文件UPLOADED状态更新失败");
            throw new RuntimeException("文档 ZIP 转换失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
            // 异步清理临时文件
            cleanupTempFilesAsync(zipFilePath, extractDir);
        }
    }

    /**
     * 解压 ZIP 文件到指定目录
     */
    private void extractZip(String zipFilePath, String extractDir) throws IOException {
        Path extractPath = Paths.get(extractDir);
        Files.createDirectories(extractPath);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = extractPath.resolve(entry.getName());

                // 安全检查：防止 ZIP 路径遍历攻击
                if (!entryPath.normalize().startsWith(extractPath.normalize())) {
                    log.warn("跳过不安全的 ZIP 条目: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 处理解压后的文件：上传 md 和图片到 MinIO，替换图片地址，生成图片描述
     */
    private String processExtractedFiles(KnowledgeDocument document, String extractDir) throws Exception {
        Path extractPath = Paths.get(extractDir);

        // 查找所有的 md 文件和图片文件
        Path mdFile = null;
        java.util.List<Path> imageFiles = new java.util.ArrayList<>();

        try (Stream<Path> paths = Files.walk(extractPath)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    String fileName = path.getFileName().toString().toLowerCase();
                    if (fileName.endsWith(".md")) {
                        mdFile = path;
                    } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                            fileName.endsWith(".jpeg") || fileName.endsWith(".gif") ||
                            fileName.endsWith(".webp") || fileName.endsWith(".bmp")) {
                        imageFiles.add(path);
                    }
                }
            }
        }

        if (mdFile == null) {
            throw new RuntimeException("解压后的文件夹中未找到 Markdown 文件");
        }

        log.info("找到 Markdown 文件: {}, 图片文件数量: {}", mdFile, imageFiles.size());

        // 上传图片到 MinIO，并建立本地文件名到 MinIO URL 的映射
        java.util.Map<String, String> imageUrlMap = new java.util.HashMap<>();
        String baseObjectName = CONVERTED_FILE_DIR + document.getDocTitle() + "/";

        for (Path imagePath : imageFiles) {
            String imageName = imagePath.getFileName().toString();
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String contentType = getImageContentType(imageName);
            String objectName = baseObjectName + "images/" + imageName;
            String imageUrl = fileStorageService.uploadFile(objectName, imageBytes, contentType);
            imageUrlMap.put(imageName, imageUrl);
            log.info("图片已上传到 MinIO: {} -> {}", imageName, imageUrl);
        }

        // 读取 md 文件内容
        String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);

        // 替换 md 中的图片地址为 MinIO 地址，并生成图片描述
        String processedMdContent = processMarkdownImages(mdContent, imageUrlMap);

        // 上传处理后的 md 文件到 MinIO
        String mdObjectName = baseObjectName + mdFile.getFileName().toString();
        String mdUrl = fileStorageService.uploadFile(mdObjectName, processedMdContent.getBytes(StandardCharsets.UTF_8), ContentType.TEXT_MARKDOWN);
        log.info("Markdown 文件已上传到 MinIO: {}", mdUrl);

        return mdUrl;
    }

    /**
     * 获取图片的 Content-Type
     */
    private String getImageContentType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".png")) return "image/png";
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return "image/jpeg";
        if (lowerName.endsWith(".gif")) return "image/gif";
        if (lowerName.endsWith(".webp")) return "image/webp";
        if (lowerName.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    /**
     * 处理 Markdown 中的图片标签：替换地址并生成图片描述
     * 匹配格式: ![](xxx.png) 或 ![alt](xxx.png)
     */
    private String processMarkdownImages(String mdContent, java.util.Map<String, String> imageUrlMap) {
        // 匹配图片标签的正则表达式: ![alt](path)
        Pattern pattern = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(mdContent);

        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String altText = matcher.group(1);
            String imagePath = matcher.group(2);

            // 提取图片文件名
            String imageName = Paths.get(imagePath).getFileName().toString();

            // 获取 MinIO 上的图片 URL
            String minioUrl = imageUrlMap.get(imageName);
            if (minioUrl == null) {
                // 如果找不到对应的 MinIO URL，保持原样
                log.warn("未找到图片 {} 对应的 MinIO URL", imageName);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            // 生成图片描述（mock 实现）
            String imageDescription = generateImageDescription(minioUrl);

            // 构建新的图片标签: ![描述](minio_url)
            String newImageTag = "![" + imageDescription + "](" + minioUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));

            log.info("图片标签已处理: {} -> {}", imagePath, minioUrl);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatModelApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatModelBaseUrl;

    /**
     * 图片描述用的本地多模态视觉模型名。
     * <p>
     * 原先硬编码为 {@code qwen3-vl-plus}——那是云端 DashScope 的视觉模型名，
     * 而 {@code langchain4j.open-ai.chat-model.base-url} 指向本机 Ollama，
     * Ollama 上并不存在该模型，图片描述必然抛 {@code ModelNotFoundException}。
     * 改为读取 {@code know-engine.vision.chat-model}（默认本地 qwen2.5vl:7b）。
     */
    @Value("${know-engine.vision.chat-model:${langchain4j.open-ai.chat-model.model-name}}")
    private String visionChatModelName;

    /**
     * 生成图片描述
     * 需要注意的是，如果你用的是外部的模型，这个url需要是公网可以访问的url。否则模型需要能和MinIO进行内网通信。
     */
    public String generateImageDescription(String imageUrl) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(chatModelApiKey)
                .baseUrl(chatModelBaseUrl)
                .modelName(visionChatModelName)
                .temperature(0.7)
                .logResponses(true)
                .logRequests(true)
                .build();

        UserMessage userMessage = UserMessage.from(new TextContent("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明，不要增加任何特殊符号，特别是换行符"), new ImageContent(imageUrl));
        return chatModel.chat(userMessage).aiMessage().text();
    }

    /**
     * 异步清理临时文件
     */
    private void cleanupTempFilesAsync(String zipFilePath, String extractDir) {
        if (zipFilePath == null && extractDir == null) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                // 删除 ZIP 文件
                if (zipFilePath != null) {
                    Files.deleteIfExists(Paths.get(zipFilePath));
                    log.info("临时 ZIP 文件已删除: {}", zipFilePath);
                }

                // 删除解压目录
                if (extractDir != null) {
                    deleteDirectory(Paths.get(extractDir));
                    log.info("临时解压目录已删除: {}", extractDir);
                }
            } catch (Exception e) {
                log.warn("清理临时文件失败", e);
            }
        });
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((a, b) -> -a.compareTo(b)) // 反向排序，先删除子文件/目录
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {}", path, e);
                        }
                    });
        }
    }

    /**
     * 调用文件解析接口
     * 使用 Apache HttpClient 5 替代 HttpURLConnection，提供更好的超时控制和连接管理
     *
     * @param fileName   文件名
     * @param fileStream 文件输入流
     * @return 解析结果
     */
    private String parseDocumentToMarkdown(String fileName, InputStream fileStream) {
        String url = fileParseApiUrl + "/file_parse";

        // 配置请求超时
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout)).setResponseTimeout(Timeout.ofMilliseconds(responseTimeout)).build();

        try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()) {

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Accept", "application/json");

            // 构建 multipart 请求体
            HttpEntity multipartEntity = MultipartEntityBuilder.create().addBinaryBody("files", fileStream, org.apache.hc.core5.http.ContentType.APPLICATION_OCTET_STREAM, fileName).addTextBody("backend", "pipeline").addTextBody("response_format_zip", "false").addTextBody("return_images", "false").addTextBody("return_model_output", "false").addTextBody("return_middle_json", "false").build();

            httpPost.setEntity(multipartEntity);

            log.info("开始调用文件解析接口: {}", url);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                log.info("文件解析接口响应状态码: {}", statusCode);

                HttpEntity responseEntity = response.getEntity();
                String responseBody = responseEntity != null ? EntityUtils.toString(responseEntity, "UTF-8") : "";

                if (statusCode == 200) {
                    log.info("文件解析接口调用成功，响应体长度: {}", responseBody.length());
                    return responseBody;
                } else {
                    log.error("文件解析接口调用失败，状态码: {}, 响应: {}", statusCode, responseBody);
                    throw new RuntimeException("文件解析接口调用失败: HTTP " + statusCode + ", " + responseBody);
                }
            }
        } catch (Exception e) {
            log.error("调用文件解析接口异常", e);
            throw new RuntimeException("调用文件解析接口失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(fileStream);
        }
    }

    /**
     * 调用文件解析接口，获取 ZIP 格式响应
     * 使用 Apache HttpClient 5，支持流式下载大文件
     *
     * @param fileName   文件名
     * @param fileStream 文件输入流
     * @return ZIP 文件字节数组
     */
    private byte[] parseDocumentToZip(String fileName, InputStream fileStream) {
        String url = fileParseApiUrl + "/file_parse";

        // 配置请求超时
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout)).setResponseTimeout(Timeout.ofMilliseconds(responseTimeout)).build();

        try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()) {

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Accept", "application/json");

            // 构建 multipart 请求体，启用 ZIP 格式和返回图片
            HttpEntity multipartEntity = MultipartEntityBuilder.create()
                    .addBinaryBody("files", fileStream, org.apache.hc.core5.http.ContentType.APPLICATION_OCTET_STREAM, fileName)
                    .addTextBody("backend", "pipeline").addTextBody("response_format_zip", "true")
                    .addTextBody("return_images", "true").addTextBody("return_model_output", "false")
                    .addTextBody("return_middle_json", "false").build();

            httpPost.setEntity(multipartEntity);

            log.info("开始调用文件解析接口（ZIP 模式）: {}", url);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                log.info("文件解析接口响应状态码: {}", statusCode);

                HttpEntity responseEntity = response.getEntity();
                if (statusCode == 200 && responseEntity != null) {
                    // 读取响应体为字节数组（ZIP 文件）
                    byte[] zipBytes = EntityUtils.toByteArray(responseEntity);
                    log.info("文件解析接口调用成功，ZIP 文件大小: {} bytes", zipBytes.length);
                    return zipBytes;
                } else {
                    String responseBody = responseEntity != null ? EntityUtils.toString(responseEntity, "UTF-8") : "";
                    log.error("文件解析接口调用失败，状态码: {}, 响应: {}", statusCode, responseBody);
                    throw new RuntimeException("文件解析接口调用失败: HTTP " + statusCode + ", " + responseBody);
                }
            }

        } catch (Exception e) {
            log.error("调用文件解析接口异常", e);
            throw new RuntimeException("调用文件解析接口失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(fileStream);
        }
    }

    /**
     * 安静关闭输入流，忽略异常
     *
     * @param inputStream 输入流
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
}

