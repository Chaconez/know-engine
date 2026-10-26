package com.chaconez.knowengine.common;

import com.chaconez.knowengine.rag.modules.reranker.BgeScoringModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点（无需登录）
 * <p>
 * 除存活状态外，暴露本地 Reranker 的实际加载情况，
 * 便于在容器化部署下确认外置模型（RERANKER_MODEL_PATH）是否真正生效，
 * 避免"模型未加载、静默降级为 RRF 融合"这类问题无法从外部观测。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> reranker = new LinkedHashMap<>();
        reranker.put("available", BgeScoringModel.isAvailable());
        reranker.put("source", BgeScoringModel.getModelSource());
        reranker.put("modelPath", BgeScoringModel.getResolvedModelPath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("reranker", reranker);
        return body;
    }
}
