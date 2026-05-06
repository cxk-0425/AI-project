package com.kb.unit;

import com.kb.agent.SupervisorAgent;
import com.kb.agent.PlanStep;
import com.kb.agent.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SupervisorAgent 单元测试
 * <p>
 * 测试重点：
 * <ul>
 *   <li>空步骤列表应直接返回 fail（不调用 LLM）</li>
 *   <li>LLM 异常时应放行（默认通过，不阻塞主流程）</li>
 *   <li>正常 JSON 输出的 pass/fail 解析</li>
 *   <li>JSON 解析失败时的兜底行为</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("SupervisorAgent 单元测试")
class SupervisorAgentTest {

    private ChatClient mockChatClient;
    private ChatClient.CallResponseSpec mockCallSpec;
    private ChatClient.ChatClientRequestSpec mockRequestSpec;
    private SupervisorAgent supervisorAgent;

    @BeforeEach
    void setUp() {
        mockChatClient = mock(ChatClient.class);
        mockCallSpec = mock(ChatClient.CallResponseSpec.class);
        mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        supervisorAgent = new SupervisorAgent(mockChatClient, new ObjectMapper());

        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.system(any(String.class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(any(String.class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallSpec);
    }

    // ─── 空步骤列表测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("空步骤列表时应直接返回 fail，不调用 LLM")
    void validate_emptySteps_shouldReturnFailWithoutCallingLlm() {
        ValidationResult result = supervisorAgent.validate("创建活动", Collections.emptyList());

        assertThat(result.isComplete()).isFalse();
        assertThat(result.missingSteps()).isNotEmpty();
        verify(mockChatClient, never()).prompt();
    }

    // ─── LLM 异常兜底测试 ────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM 调用失败时应默认放行（返回 complete=true），不传播异常")
    void validate_llmThrowsException_shouldPassByDefault() {
        List<PlanStep> steps = sampleSteps();
        when(mockRequestSpec.call()).thenThrow(new RuntimeException("LLM unavailable"));

        ValidationResult result = supervisorAgent.validate("创建活动", steps);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.missingSteps()).isEmpty();
    }

    @Test
    @DisplayName("LLM 调用失败时不应向上传播异常")
    void validate_llmThrowsException_shouldNotThrowException() {
        List<PlanStep> steps = sampleSteps();
        when(mockRequestSpec.call()).thenThrow(new RuntimeException("LLM unavailable"));

        assertThatCode(() -> supervisorAgent.validate("创建活动", steps))
                .doesNotThrowAnyException();
    }

    // ─── JSON 解析测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM 返回 complete=true 时应解析为通过")
    void validate_llmReturnsComplete_shouldPass() {
        List<PlanStep> steps = sampleSteps();
        String response = """
                {"complete": true, "missingSteps": [], "comment": "步骤完整，逻辑正确"}
                """;
        when(mockCallSpec.content()).thenReturn(response);

        ValidationResult result = supervisorAgent.validate("创建活动", steps);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.missingSteps()).isEmpty();
    }

    @Test
    @DisplayName("LLM 返回 complete=false 时应解析缺失步骤")
    void validate_llmReturnsFail_shouldExtractMissingSteps() {
        List<PlanStep> steps = List.of(
                new PlanStep(1, "createActivity", "创建COUPON活动", Map.of(), List.of())
        );
        String response = """
                {"complete": false, "missingSteps": ["缺少 createCoupon 步骤"], "comment": "COUPON活动必须创建优惠券"}
                """;
        when(mockCallSpec.content()).thenReturn(response);

        ValidationResult result = supervisorAgent.validate("创建COUPON活动并发放", steps);

        assertThat(result.isComplete()).isFalse();
        assertThat(result.missingSteps()).hasSize(1);
        assertThat(result.missingSteps().get(0)).contains("createCoupon");
    }

    @Test
    @DisplayName("LLM 返回无效 JSON 时应默认放行（兜底 complete=true）")
    void validate_llmReturnsInvalidJson_shouldPassByDefault() {
        List<PlanStep> steps = sampleSteps();
        when(mockCallSpec.content()).thenReturn("无法审核，需要更多信息");

        ValidationResult result = supervisorAgent.validate("创建活动", steps);

        assertThat(result.isComplete()).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 null 时应默认放行")
    void validate_llmReturnsNull_shouldPassByDefault() {
        List<PlanStep> steps = sampleSteps();
        when(mockCallSpec.content()).thenReturn(null);

        ValidationResult result = supervisorAgent.validate("创建活动", steps);

        assertThat(result.isComplete()).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 complete=false 但 missingSteps 为空时也应返回 fail")
    void validate_llmReturnsFalseWithEmptyMissingSteps_shouldBeFail() {
        List<PlanStep> steps = sampleSteps();
        String response = """
                {"complete": false, "missingSteps": [], "comment": "步骤不完整"}
                """;
        when(mockCallSpec.content()).thenReturn(response);

        ValidationResult result = supervisorAgent.validate("创建活动", steps);

        assertThat(result.isComplete()).isFalse();
    }

    @Test
    @DisplayName("LLM 返回带前缀文字的 JSON 时也应正确解析")
    void validate_llmReturnsJsonWithPrefix_shouldParseProperly() {
        List<PlanStep> steps = sampleSteps();
        String response = """
                经过审核，步骤如下：
                {"complete": true, "missingSteps": [], "comment": "审核通过"}
                希望对您有帮助。
                """;
        when(mockCallSpec.content()).thenReturn(response);

        ValidationResult result = supervisorAgent.validate("创建活动", steps);

        assertThat(result.isComplete()).isTrue();
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    private List<PlanStep> sampleSteps() {
        return List.of(
                new PlanStep(1, "createActivity", "创建满减活动", Map.of("type", "FULL_REDUCTION"), List.of()),
                new PlanStep(2, "configureActivityRules", "配置满100减20规则",
                        Map.of("threshold", 100, "discount", 20), List.of(1))
        );
    }
}
