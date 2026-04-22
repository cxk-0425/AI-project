package com.kb.intent;

/**
 * 意图识别结果来源
 */
public enum IntentSource {

    /** 由 BERT 模型分类得出 */
    BERT,

    /** 由 LLM（OpenAI）降级分类得出 */
    LLM,

    /** 默认降级（BERT 不可用且 LLM 置信度不足，默认 QUERY）*/
    FALLBACK
}
