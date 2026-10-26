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
     * 外置模型路径的环境变量名
     * <p>
     * 用于容器化部署：模型文件（~570MB）不打入 jar，而是通过挂载卷提供，
     * 由环境变量指定绝对路径。未设置时回退到 classpath，仍未找到则降级。
     */
    private static final String ENV_MODEL_PATH = "RERANKER_MODEL_PATH";
    private static final String ENV_TOKENIZER_PATH = "RERANKER_TOKENIZER_PATH";

    /**
     * 单例实例，使用 volatile 保证多线程可见性
     */
    private static volatile OnnxScoringModel instance;

    /**
     * 模型是否已尝试加载并失败，用于避免每次问答都重试加载数百 MB 的模型
     */
    private static volatile boolean loadFailed = false;

    /**
     * 实际解析到的模型路径与来源，仅用于健康检查与问题排查
     */
    private static volatile String resolvedModelPath;
    private static volatile String modelSource = "none";

    /**
     * @return 实际使用的模型文件路径；未加载时为 null
     */
    public static String getResolvedModelPath() {
        return resolvedModelPath;
    }

    /**
     * @return 模型来源：env:RERANKER_MODEL_PATH / classpath / none
     */
    public static String getModelSource() {
        return modelSource;
    }

    /**
     * Spring 容器启动时预加载模型，避免首次问答时触发模型加载导致延迟
     * <p>
     * 模型文件为可选项（仓库不含模型二进制，超 GitHub 单文件 100MB 限制），
     * 缺失时仅记录告警并降级，不阻断容器启动。
     */
    @PostConstruct
    public void init() {
        tryLoadModel();
    }

    /**
     * 本地 Reranker 模型是否可用（即模型文件已放置且加载成功）
     *
     * @return true 表示可启用精排，false 表示需降级为 RRF 融合
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * 获取 OnnxScoringModel 单例实例
     * <p>
     * 正常情况下模型已在 {@link #init()} 中预加载完成，直接返回实例。
     * 若在 Spring 容器初始化完成前被调用（如单元测试），则降级为懒加载。
     * 模型文件缺失时返回 {@code null}，调用方需先用 {@link #isAvailable()} 判断。
     *
     * @return OnnxScoringModel 实例，模型不可用时为 null
     */
    public static OnnxScoringModel getInstance() {
        if (instance == null) {
            tryLoadModel();
        }
        return instance;
    }

    /**
     * 尝试加载模型，失败时标记 loadFailed 并记录告警，不向外抛出异常
     */
    private static synchronized void tryLoadModel() {
        if (instance != null || loadFailed) {
            return;
        }
        try {
            loadModel();
        } catch (Exception e) {
            loadFailed = true;
            log.warn("BGE-RERANKER 模型未加载，Reranking 将降级为 RRF 融合，不影响其余功能。" +
                            "如需启用本地精排，请将 model_quantized.onnx 与 tokenizer.json 放入 " +
                            "src/main/resources/model/bge-reranker-model/。原因：{}",
                    e.getMessage());
        }
    }

    /**
     * 加载 ONNX 模型与 tokenizer，初始化 OnnxScoringModel 实例
     */
    private static synchronized void loadModel() {
        if (instance != null) {
            return;
        }
        String modelPath = resolveModelPath(CLASSPATH_MODEL, ENV_MODEL_PATH);
        String tokenizerPath = resolveModelPath(CLASSPATH_TOKENIZER, ENV_TOKENIZER_PATH);
        resolvedModelPath = modelPath;
        String envModel = System.getenv(ENV_MODEL_PATH);
        modelSource = (envModel != null && !envModel.isBlank()) ? "env:" + ENV_MODEL_PATH : "classpath";

        log.info("正在初始化 BGE-RERANKER 评分模型...");
        log.info("模型路径: {}", modelPath);
        log.info("Tokenizer路径: {}", tokenizerPath);

        instance = new OnnxScoringModel(modelPath, tokenizerPath, 8192);

        log.info("BGE-RERANKER 评分模型初始化完成");
    }

    /**
     * 解析模型文件路径，优先级：外置环境变量 > classpath 资源
     * <p>
     * 容器化部署时通过 {@code RERANKER_MODEL_PATH} / {@code RERANKER_TOKENIZER_PATH}
     * 指定挂载卷中的绝对路径，避免把数百 MB 的模型打进 jar；
     * 未设置或指向的文件不存在时，回退到 classpath 资源。
     *
     * @param classpathResource classpath 下的资源名称（兜底）
     * @param envVar            外置路径的环境变量名
     * @return 模型文件的绝对路径
     */
    private static String resolveModelPath(String classpathResource, String envVar) {
        String external = System.getenv(envVar);
        if (external != null && !external.isBlank()) {
            File externalFile = new File(external.trim());
            if (externalFile.isFile()) {
                log.info("使用外置模型文件（来自环境变量 {}）: {}", envVar, externalFile.getAbsolutePath());
                return externalFile.getAbsolutePath();
            }
            log.warn("环境变量 {} 指向的文件不存在，回退到 classpath: {}", envVar, external.trim());
        }
        return resolveClasspathToFilePath(classpathResource);
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
