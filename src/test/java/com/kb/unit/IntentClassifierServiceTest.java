package com.kb.unit;

import com.kb.intent.BertIntentClassifier;
import com.kb.intent.IntentClassifierService;
import com.kb.intent.IntentResult;
import com.kb.intent.IntentSource;
import com.kb.intent.IntentType;
import com.kb.intent.LlmIntentClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * IntentClassifierService 单元测试
 * <p>
 * 验证三层降级链路（BERT → LLM → FALLBACK）的逻辑分支：
 * <ul>
 *   <li>BERT 高置信度命中，直接返回（不调用 LLM）</li>
 *   <li>BERT 置信度不足，降级到 LLM</li>
 *   <li>BERT 服务不可用（抛异常），降级到 LLM</li>
 *   <li>BERT 禁用时，跳过 BERT，直接走 LLM</li>
 *   <li>LLM 置信度不足，最终 FALLBACK 为 QUERY</li>
 *   <li>LLM 抛异常，最终 FALLBACK 为 QUERY</li>
 *   <li>空/null query，直接返回 FALLBACK QUERY</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("IntentClassifierService 三层降级逻辑单元测试")
class IntentClassifierServiceTest {

    private BertIntentClassifier mockBertClassifier;
    private LlmIntentClassifier mockLlmClassifier;
    private IntentClassifierService classifierService;

    @BeforeEach
    void setUp() {
        mockBertClassifier = mock(BertIntentClassifier.class);
        mockLlmClassifier = mock(LlmIntentClassifier.class);
        classifierService = new IntentClassifierService(mockBertClassifier, mockLlmClassifier);
        // 默认配置
        ReflectionTestUtils.setField(classifierService, "bertEnabled", true);
        ReflectionTestUtils.setField(classifierService, "fallbackThreshold", 0.65);
    }

    // ─── BERT 路径测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("BERT 高置信度命中时，不调用 LLM 直接返回")
    void classify_bertHighConfidence_shouldSkipLlm() {
        IntentResult bertResult = new IntentResult(IntentType.QUERY, 0.95, IntentSource.BERT);
        when(mockBertClassifier.classify(anyString())).thenReturn(bertResult);

        IntentResult result = classifierService.classify("当前有哪些活动？");

        assertThat(result.getType()).isEqualTo(IntentType.QUERY);
        assertThat(result.getSource()).isEqualTo(IntentSource.BERT);
        verify(mockLlmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("BERT 置信度不足（低于 isHighConfidence 阈值），应降级到 LLM")
    void classify_bertLowConfidence_shouldFallbackToLlm() {
        // BERT 返回低置信度结果
        IntentResult lowConfBert = new IntentResult(IntentType.QUERY, 0.60, IntentSource.BERT);
        IntentResult llmResult = new IntentResult(IntentType.CONFIG, 0.88, IntentSource.LLM);
        when(mockBertClassifier.classify(anyString())).thenReturn(lowConfBert);
        when(mockLlmClassifier.classify(anyString())).thenReturn(llmResult);

        IntentResult result = classifierService.classify("帮我创建活动");

        assertThat(result.getType()).isEqualTo(IntentType.CONFIG);
        assertThat(result.getSource()).isEqualTo(IntentSource.LLM);
        verify(mockLlmClassifier, times(1)).classify(anyString());
    }

    @Test
    @DisplayName("BERT 服务抛异常时，应安全降级到 LLM")
    void classify_bertThrowsException_shouldFallbackToLlm() {
        when(mockBertClassifier.classify(anyString()))
                .thenThrow(new RuntimeException("BERT service unavailable"));
        IntentResult llmResult = new IntentResult(IntentType.CONFIG, 0.85, IntentSource.LLM);
        when(mockLlmClassifier.classify(anyString())).thenReturn(llmResult);

        IntentResult result = classifierService.classify("创建一个活动");

        assertThat(result.getSource()).isEqualTo(IntentSource.LLM);
        assertThat(result.getType()).isEqualTo(IntentType.CONFIG);
    }

    @Test
    @DisplayName("bert.enabled=false 时，应跳过 BERT，直接走 LLM")
    void classify_bertDisabled_shouldSkipBertDirectlyCallLlm() {
        ReflectionTestUtils.setField(classifierService, "bertEnabled", false);
        IntentResult llmResult = new IntentResult(IntentType.QUERY, 0.90, IntentSource.LLM);
        when(mockLlmClassifier.classify(anyString())).thenReturn(llmResult);

        classifierService.classify("查一下活动状态");

        verify(mockBertClassifier, never()).classify(anyString());
        verify(mockLlmClassifier, times(1)).classify(anyString());
    }

    // ─── LLM 路径测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM 置信度高于 fallbackThreshold，应返回 LLM 结果")
    void classify_llmHighConfidence_shouldReturnLlmResult() {
        ReflectionTestUtils.setField(classifierService, "bertEnabled", false);
        IntentResult llmResult = new IntentResult(IntentType.CONFIG, 0.80, IntentSource.LLM);
        when(mockLlmClassifier.classify(anyString())).thenReturn(llmResult);

        IntentResult result = classifierService.classify("修改活动时间");

        assertThat(result.getType()).isEqualTo(IntentType.CONFIG);
        assertThat(result.getConfidence()).isEqualTo(0.80);
    }

    @Test
    @DisplayName("LLM 置信度低于 fallbackThreshold，应最终 FALLBACK 为 QUERY")
    void classify_llmLowConfidence_shouldFallbackToDefaultQuery() {
        ReflectionTestUtils.setField(classifierService, "bertEnabled", false);
        IntentResult lowConfLlm = new IntentResult(IntentType.UNKNOWN, 0.40, IntentSource.LLM);
        when(mockLlmClassifier.classify(anyString())).thenReturn(lowConfLlm);

        IntentResult result = classifierService.classify("能不能帮我");

        assertThat(result.getType()).isEqualTo(IntentType.QUERY);
        assertThat(result.getSource()).isEqualTo(IntentSource.FALLBACK);
    }

    @Test
    @DisplayName("LLM 抛异常时，应 FALLBACK 为 QUERY（不向上传播异常）")
    void classify_llmThrowsException_shouldFallbackToDefaultQuery() {
        ReflectionTestUtils.setField(classifierService, "bertEnabled", false);
        when(mockLlmClassifier.classify(anyString()))
                .thenThrow(new RuntimeException("OpenAI API timeout"));

        IntentResult result = classifierService.classify("随便问一句");

        assertThat(result.getType()).isEqualTo(IntentType.QUERY);
        assertThat(result.getSource()).isEqualTo(IntentSource.FALLBACK);
    }

    // ─── 边界场景测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("空字符串 query，应直接返回 FALLBACK QUERY（不调用任何分类器）")
    void classify_emptyQuery_shouldReturnDefaultQueryWithoutCallingClassifiers() {
        IntentResult result = classifierService.classify("");

        assertThat(result.getType()).isEqualTo(IntentType.QUERY);
        assertThat(result.getSource()).isEqualTo(IntentSource.FALLBACK);
        verify(mockBertClassifier, never()).classify(anyString());
        verify(mockLlmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("null query，应直接返回 FALLBACK QUERY（不调用任何分类器）")
    void classify_nullQuery_shouldReturnDefaultQueryWithoutCallingClassifiers() {
        IntentResult result = classifierService.classify(null);

        assertThat(result.getType()).isEqualTo(IntentType.QUERY);
        assertThat(result.getSource()).isEqualTo(IntentSource.FALLBACK);
        verify(mockBertClassifier, never()).classify(anyString());
        verify(mockLlmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("BERT 和 LLM 都失败时，最终应返回非 null 的 FALLBACK QUERY")
    void classify_allClassifiersFail_shouldAlwaysReturnNonNull() {
        when(mockBertClassifier.classify(anyString()))
                .thenThrow(new RuntimeException("BERT down"));
        when(mockLlmClassifier.classify(anyString()))
                .thenThrow(new RuntimeException("LLM down"));

        IntentResult result = classifierService.classify("任意查询");

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(IntentType.QUERY);
        assertThat(result.getSource()).isEqualTo(IntentSource.FALLBACK);
    }

    @Test
    @DisplayName("LLM 返回恰好等于 fallbackThreshold 的置信度时，应视为有效（>=阈值）")
    void classify_llmConfidenceEqualsThreshold_shouldBeValid() {
        ReflectionTestUtils.setField(classifierService, "bertEnabled", false);
        IntentResult borderLineResult = new IntentResult(IntentType.QUERY, 0.65, IntentSource.LLM);
        when(mockLlmClassifier.classify(anyString())).thenReturn(borderLineResult);

        IntentResult result = classifierService.classify("查询活动");

        assertThat(result.getSource()).isEqualTo(IntentSource.LLM);
        assertThat(result.getConfidence()).isEqualTo(0.65);
    }
}
