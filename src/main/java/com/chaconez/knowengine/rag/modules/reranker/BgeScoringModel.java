package com.chaconez.knowengine.rag.modules.reranker;


import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * BGE-RERANKER 评分模型的单例封装
 * <p>
 * 基于 OnnxScoringModel，在 Java 进程内通过 ONNX Runtime 本地运行 BGE-RERANKER 模型，
 * 用于 RAG 流程中的文档重排序（Reranking）。
 * <p>
 * 使用方式：
 * <pre>
 * OnnxScoringModel scoringModel = BgeScoringModel.getInstance();
 * ContentAggregator aggregator = ReRankingContentAggregator.builder()
 *     .scoringModel(scoringModel)
 *     .minScore(0.5)
 *     .build();
 * </pre>
 * <p>
 * 模型文件获取：
 * 1. 从 HuggingFace 下载 ONNX 格式的 BGE-RERANKER 模型，如：
 * - onnx-community/bge-reranker-v2-m3-ONNX（推荐中文场景）
 * 2. 需要 model.onnx（或 model_quantized.onnx）和 tokenizer.json 两个文件
 * 3. 放置到 modelPath 和 tokenizerPath 指定的路径下
 */
@Slf4j
@Component
public class BgeScoringModel {

    /**
     * classpath 下的模型文件路径
     * 需要先下载对应的文件，放到 resources 目录下
     */
    private static final String CLASSPATH_MODEL = "model/bge-reranker-model/model_quantized.onnx";
    private static final String CLASSPATH_TOKENIZER = "model/bge-reranker-model/tokenizer.json";

    /**
     * 单例实例，使用 volatile 保证多线程可见性
     */
    private static volatile OnnxScoringModel instance;

    /**
     * Spring 容器启动时预加载模型，避免首次问答时触发模型加载导致延迟
     */
    @PostConstruct
    public void init() {
        if (instance == null) {
            loadModel();
        }
    }

    /**
     * 获取 OnnxScoringModel 单例实例
     * <p>
     * 正常情况下模型已在 {@link #init()} 中预加载完成，直接返回实例。
     * 若在 Spring 容器初始化完成前被调用（如单元测试），则降级为懒加载。
     *
     * @return OnnxScoringModel 实例
     */
    public static OnnxScoringModel getInstance() {
        if (instance == null) {
            synchronized (BgeScoringModel.class) {
                if (instance == null) {
                    loadModel();
                }
            }
        }
        return instance;
    }

    /**
     * 加载 ONNX 模型与 tokenizer，初始化 OnnxScoringModel 实例
     */
    private static synchronized void loadModel() {
        if (instance != null) {
            return;
        }
        String modelPath = resolveClasspathToFilePath(CLASSPATH_MODEL);
        String tokenizerPath = resolveClasspathToFilePath(CLASSPATH_TOKENIZER);

        log.info("正在初始化 BGE-RERANKER 评分模型...");
        log.info("模型路径: {}", modelPath);
        log.info("Tokenizer路径: {}", tokenizerPath);

        instance = new OnnxScoringModel(modelPath, tokenizerPath, 8192);

        log.info("BGE-RERANKER 评分模型初始化完成");
    }

    /**
     * 将 classpath 资源解析为文件绝对路径
     * <p>
     * 优先尝试直接获取文件路径（IDE 或解压目录下的资源），
     * 如果资源在 JAR 包内则复制到临时文件后返回临时文件路径。
     *
     * @param classpathResource classpath 下的资源名称
     * @return 资源的绝对文件路径
     */
    private static String resolveClasspathToFilePath(String classpathResource) {
        URL resource = BgeScoringModel.class.getClassLoader().getResource(classpathResource);
        if (resource == null) {
            throw new IllegalArgumentException(
                    String.format("classpath 下未找到资源: %s，请确认模型文件已放置到 resources 目录", classpathResource));
        }

        // 尝试直接获取文件路径（适用于 IDE 运行或解压目录）
        try {
            File file = new File(resource.toURI());
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            // 资源在 JAR 包内，无法直接转 File，走下方临时文件逻辑
            log.debug("资源在 JAR 包内，将复制到临时文件: {}", classpathResource);
        }

        // 资源在 JAR 包内，复制到临时文件
        try (InputStream is = BgeScoringModel.class.getClassLoader().getResourceAsStream(classpathResource)) {
            Path tempFile = Files.createTempFile("bge-reranker-", "." + classpathResource.substring(classpathResource.lastIndexOf('.') + 1));
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("已将 classpath 资源复制到临时文件: {}", tempFile.toAbsolutePath());
            return tempFile.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("无法从 classpath 复制资源到临时文件: " + classpathResource, e);
        }
    }
}
