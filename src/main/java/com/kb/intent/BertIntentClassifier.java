package com.kb.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * BERT 意图分类器（主路径）
 * <p>
 * 调用外部 Python FastAPI 部署的 BERT 文本分类服务，
 * 将用户 Query 分类为 QUERY（查询类）或 CONFIG（配置类）。
 * <p>
 * BERT 服务 API 约定：
 * POST /classify
 * Request:  {"text": "用户输入"}
 * Response: {"label": "QUERY|CONFIG|UNKNOWN", "confidence": 0.95}
 * <p>
 * 若 BERT 服务不可用（抛出异常）或置信度低于阈值，
 * 由上层 IntentClassifierService 自动降级到 LlmIntentClassifier。
 */
@Slf4j
@Service
public class BertIntentClassifier {

    private final RestClient bertRestClient;

    @Value("${bert.confidence-threshold:0.82}")
    private double confidenceThreshold;

    public BertIntentClassifier(
            @Value("${bert.service.url:http://localhost:8000}") String bertServiceUrl) {
        this.bertRestClient = RestClient.builder()
                .baseUrl(bertServiceUrl)
                .build();
    }

    /**
     * 调用 BERT 服务进行意图分类
     *
     * @param query 用户原始查询文本
     * @return 意图识别结果，置信度不足时返回 lowConfidence()
     * @throws Exception BERT 服务不可用时抛出，由上层处理降级
     */
    public IntentResult classify(String query) {
        log.debug("[BertIntentClassifier] 调用 BERT 分类，query={}", query);

        BertClassifyRequest request = new BertClassifyRequest(query);

        BertClassifyResponse response = bertRestClient
                .post()
                .uri("/classify")
                .body(request)
                .retrieve()
                .body(BertClassifyResponse.class);

        if (response == null) {
            log.warn("[BertIntentClassifier] BERT 服务返回空响应");
            return IntentResult.lowConfidence();
        }

        log.info("[BertIntentClassifier] 分类结果: label={}, confidence={:.3f}",
                response.label(), response.confidence());

        // 置信度检验
        if (response.confidence() < confidenceThreshold) {
            log.info("[BertIntentClassifier] 置信度 {:.3f} 低于阈值 {:.3f}，触发 LLM 降级",
                    response.confidence(), confidenceThreshold);
            return IntentResult.lowConfidence();
        }

        // 将 BERT label 转换为 IntentType（若 label 不合法则视为 UNKNOWN）
        IntentType intentType;
        try {
            intentType = IntentType.valueOf(response.label().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[BertIntentClassifier] 未知 label: {}，降级处理", response.label());
            return IntentResult.lowConfidence();
        }

        return new IntentResult(intentType, response.confidence(), IntentSource.BERT);
    }

    // ========== 内部 DTO ==========

    private record BertClassifyRequest(String text) {}

    private record BertClassifyResponse(String label, double confidence) {}
}
