package com.kb.intent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 基于 LLM 的意图分类器（降级路径）
 * <p>
 * 当 BERT 服务不可用或置信度不足时，使用 Spring AI ChatClient 结合
 * Few-Shot Prompt + temperature=0 做结构化意图分类。
 * <p>
 * 优势：
 * - 无需额外部署，直接复用现有 OpenAI ChatClient
 * - 通过 Few-Shot 示例提升分类准确率
 * - Spring AI 的 .entity() 确保 JSON 结构化输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmIntentClassifier {

    private final ChatClient chatClient;

    @Value("${intent.llm.max-tokens:150}")
    private int maxTokens;

    /**
     * 使用 LLM 进行意图分类（结构化输出）
     *
     * @param query 用户原始查询
     * @return 意图分类结果
     */
    public IntentResult classify(String query) {
        log.debug("[LlmIntentClassifier] 开始 LLM 意图分类，query={}", query);

        try {
            IntentClassification classification = chatClient
                    .prompt()
                    .system(buildSystemPrompt())
                    .user(buildUserPrompt(query))
                    .call()
                    .entity(IntentClassification.class);

            if (classification == null) {
                log.warn("[LlmIntentClassifier] LLM 返回空结果，降级为默认 QUERY");
                return IntentResult.defaultQuery();
            }

            IntentType intentType;
            try {
                intentType = IntentType.valueOf(classification.intent().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[LlmIntentClassifier] 未知 intent 值: {}，降级为 QUERY", classification.intent());
                intentType = IntentType.QUERY;
            }

            log.info("[LlmIntentClassifier] 分类结果: type={}, confidence={:.3f}, reason={}",
                    intentType, classification.confidence(), classification.reasoning());

            return new IntentResult(intentType, classification.confidence(), IntentSource.LLM);

        } catch (Exception e) {
            log.error("[LlmIntentClassifier] LLM 分类异常，降级为默认 QUERY", e);
            return IntentResult.defaultQuery();
        }
    }

    private String buildSystemPrompt() {
        return """
                你是一个营销系统的意图分类器，负责将用户输入分类为以下两种意图之一：
                
                **QUERY（查询类）**：用户想查看、检索、了解信息，包括：
                - 查询活动列表、活动详情、活动状态
                - 了解某类活动的定义、规则、条件
                - 查看当前配置、历史记录
                - 询问"是什么"、"怎么样"、"有哪些"类问题
                示例：
                  用户："当前有哪些优惠券活动？" → QUERY
                  用户："满减活动的规则是什么？" → QUERY
                  用户："查一下双十一活动的状态" → QUERY
                
                **CONFIG（配置类）**：用户想创建、修改、删除、执行操作，包括：
                - 创建新活动、配置活动参数
                - 修改现有活动（时间、规则、状态）
                - 启用/禁用活动
                - 发放优惠券、设置规则
                示例：
                  用户："帮我创建一个双十一满减活动" → CONFIG
                  用户："把活动截止时间改到明天" → CONFIG
                  用户："上线618优惠券活动" → CONFIG
                
                **UNKNOWN**：语义模糊、无法确定或与营销系统无关。
                
                请严格按以下 JSON 格式返回（不要有任何额外文字）：
                {"intent": "QUERY|CONFIG|UNKNOWN", "confidence": 0.0-1.0, "reasoning": "一句话理由"}
                """;
    }

    private String buildUserPrompt(String query) {
        return "请对以下用户输入进行意图分类：\n\n用户输入：" + query;
    }

    // ========== 内部 DTO ==========

    /**
     * LLM 结构化输出的意图分类 DTO
     */
    private record IntentClassification(
            @JsonProperty("intent") String intent,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("reasoning") String reasoning
    ) {}
}
