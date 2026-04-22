package com.kb.intent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 意图识别统一入口
 * <p>
 * 双层策略：BERT（主路径，低延迟）→ LLM 降级（兜底，高可用）→ 默认 QUERY（最后保守策略）
 * <p>
 * 调用方只需调用 {@link #classify(String)}，无需关心底层使用 BERT 还是 LLM。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code bert.enabled} - 是否启用 BERT 主路径（默认 false，本地开发时可关闭）</li>
 *   <li>{@code intent.fallback-threshold} - LLM 分类置信度最低阈值（默认 0.65）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifierService {

    private final BertIntentClassifier bertClassifier;
    private final LlmIntentClassifier llmClassifier;

    @Value("${bert.enabled:false}")
    private boolean bertEnabled;

    @Value("${intent.fallback-threshold:0.65}")
    private double fallbackThreshold;

    /**
     * 对用户 Query 进行意图分类
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>若 {@code bert.enabled=true}，先尝试 BERT 分类（高置信度时直接返回）</li>
     *   <li>BERT 置信度不足或不可用时，降级到 LLM 分类</li>
     *   <li>LLM 置信度仍不足时，默认返回 QUERY（保守策略）</li>
     * </ol>
     *
     * @param query 用户原始输入
     * @return {@link IntentResult}，永不为 null
     */
    public IntentResult classify(String query) {
        if (query == null || query.isBlank()) {
            return IntentResult.defaultQuery();
        }

        log.info("[IntentClassifierService] 开始意图分类，query={}", query);

        // Step 1: 尝试 BERT（若配置启用）
        if (bertEnabled) {
            try {
                IntentResult bertResult = bertClassifier.classify(query);
                if (bertResult.isHighConfidence()) {
                    log.info("[IntentClassifierService] BERT 高置信度命中: {}", bertResult);
                    return bertResult;
                }
                log.info("[IntentClassifierService] BERT 置信度不足 ({:.3f})，降级到 LLM",
                        bertResult.getConfidence());
            } catch (Exception e) {
                log.warn("[IntentClassifierService] BERT 服务不可用，降级到 LLM 分类: {}", e.getMessage());
            }
        } else {
            log.debug("[IntentClassifierService] BERT 未启用（bert.enabled=false），直接走 LLM");
        }

        // Step 2: LLM 降级分类
        try {
            IntentResult llmResult = llmClassifier.classify(query);
            if (llmResult.getConfidence() >= fallbackThreshold) {
                log.info("[IntentClassifierService] LLM 分类结果: {}", llmResult);
                return llmResult;
            }
            log.warn("[IntentClassifierService] LLM 置信度 {:.3f} 低于阈值 {:.3f}，采用默认 QUERY 策略",
                    llmResult.getConfidence(), fallbackThreshold);
        } catch (Exception e) {
            log.error("[IntentClassifierService] LLM 分类失败，采用默认 QUERY 策略", e);
        }

        // Step 3: 默认降级 → QUERY（保守策略）
        log.info("[IntentClassifierService] 置信度不足，降级为默认 QUERY 意图");
        return IntentResult.defaultQuery();
    }
}
