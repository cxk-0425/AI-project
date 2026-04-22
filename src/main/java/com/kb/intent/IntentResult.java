package com.kb.intent;

import lombok.Getter;

/**
 * 意图识别结果
 */
@Getter
public class IntentResult {

    private final IntentType type;
    private final double confidence;
    private final IntentSource source;

    public IntentResult(IntentType type, double confidence, IntentSource source) {
        this.type = type;
        this.confidence = confidence;
        this.source = source;
    }

    /**
     * 是否高置信度（可以自动执行）
     */
    public boolean isHighConfidence() {
        return confidence >= 0.82;
    }

    /**
     * 创建低置信度降级结果（触发 LLM 降级）
     */
    public static IntentResult lowConfidence() {
        return new IntentResult(IntentType.UNKNOWN, 0.0, IntentSource.FALLBACK);
    }

    /**
     * 默认降级：QUERY（保守策略，不确定时按查询处理）
     */
    public static IntentResult defaultQuery() {
        return new IntentResult(IntentType.QUERY, 0.5, IntentSource.FALLBACK);
    }

    @Override
    public String toString() {
        return String.format("IntentResult{type=%s, confidence=%.3f, source=%s}",
                type, confidence, source);
    }
}
