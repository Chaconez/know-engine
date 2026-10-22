package com.chaconez.knowengine.ai.service;

import com.chaconez.knowengine.ai.constant.KnowEngineIntent;
import com.chaconez.knowengine.ai.model.IntentRecognitionResult;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PromptService {
    private final Map<KnowEngineIntent, String> promptCache = new ConcurrentHashMap<>();
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * 根据意图获取提示词
     */
    public String getPrompt(KnowEngineIntent intent) {
        return promptCache.computeIfAbsent(intent, this::loadPromptFromFile);
    }

    /**
     * 根据意图获取提示词
     */
    public String getPrompt(IntentRecognitionResult intent) {
        return promptCache.computeIfAbsent(KnowEngineIntent.getIntent(intent), this::loadPromptFromFile);
    }

    /**
     * 从文件加载提示词（带缓存）
     */
    private String loadPromptFromFile(KnowEngineIntent intent) {
        try {
            Resource resource = resolver.getResource("classpath:/prompts/" + intent.getFileName());
            return FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 如果指定意图的文件不存在，返回默认提示词
            if (intent != KnowEngineIntent.CAR_OTHER) {
                return getPrompt(KnowEngineIntent.CAR_OTHER);
            }
            throw new RuntimeException("默认提示词文件缺失", e);
        }
    }
}
