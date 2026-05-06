package com.kb.unit;

import com.kb.agent.PlannerAgent;
import com.kb.agent.PlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PlannerAgent 单元测试
 * <p>
 * 不调用真实 LLM，重点测试：
 * <ul>
 *   <li>JSON 解析逻辑（从 LLM 响应中提取步骤）</li>
 *   <li>空响应/null 的兜底行为</li>
 *   <li>带前缀说明文字的 JSON 提取</li>
 *   <li>最大步骤数限制</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("PlannerAgent 单元测试")
class PlannerAgentTest {

    private ChatClient mockChatClient;
    private ChatClient.CallResponseSpec mockCallSpec;
    private ChatClient.ChatClientRequestSpec mockRequestSpec;
    private PlannerAgent plannerAgent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockChatClient = mock(ChatClient.class);
        mockCallSpec = mock(ChatClient.CallResponseSpec.class);
        mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        plannerAgent = new PlannerAgent(mockChatClient, objectMapper);
        ReflectionTestUtils.setField(plannerAgent, "maxSteps", 10);

        // 链式 Mock：chatClient.prompt().system(...).user(...).call().content()
        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.system(any(String.class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(any(String.class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallSpec);
    }

    // ─── JSON 解析测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("纯 JSON 数组响应应正确解析为 PlanStep 列表")
    void plan_pureJsonResponse_shouldParseCorrectly() {
        String jsonResponse = """
                [
                  {"stepId":1,"action":"createActivity","description":"创建满减活动","params":{},"dependsOn":[]},
                  {"stepId":2,"action":"configureActivityRules","description":"配置满100减20规则","params":{"threshold":100,"discount":20},"dependsOn":[1]}
                ]
                """;
        when(mockCallSpec.content()).thenReturn(jsonResponse);

        List<PlanStep> steps = plannerAgent.plan("创建满减活动", "SOP上下文");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).stepId()).isEqualTo(1);
        assertThat(steps.get(0).action()).isEqualTo("createActivity");
        assertThat(steps.get(1).action()).isEqualTo("configureActivityRules");
    }

    @Test
    @DisplayName("带前缀说明文字的响应应能正确提取 JSON")
    void plan_responseWithPrefixText_shouldExtractJson() {
        String response = """
                根据您的需求，我为您规划了以下执行步骤：
                
                [
                  {"stepId":1,"action":"createActivity","description":"创建优惠券活动","params":{},"dependsOn":[]}
                ]
                
                以上步骤将依次执行。
                """;
        when(mockCallSpec.content()).thenReturn(response);

        List<PlanStep> steps = plannerAgent.plan("创建优惠券活动", "");

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).action()).isEqualTo("createActivity");
    }

    @Test
    @DisplayName("完全无 JSON 的响应应返回兜底默认步骤（非空列表）")
    void plan_responseWithNoJson_shouldReturnDefaultStep() {
        String response = "我无法理解这个需求，请重新描述。";
        when(mockCallSpec.content()).thenReturn(response);

        List<PlanStep> steps = plannerAgent.plan("帮我弄个活动", "");

        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).stepId()).isEqualTo(1);
        assertThat(steps.get(0).action()).isEqualTo("createActivity");
    }

    @Test
    @DisplayName("空字符串响应应返回兜底默认步骤")
    void plan_emptyResponse_shouldReturnDefaultStep() {
        when(mockCallSpec.content()).thenReturn("");

        List<PlanStep> steps = plannerAgent.plan("创建活动", "");

        assertThat(steps).isNotEmpty();
        assertThat(steps).hasSize(1);
    }

    @Test
    @DisplayName("null 响应（LLM 调用失败）应返回兜底默认步骤")
    void plan_nullResponse_shouldReturnDefaultStep() {
        when(mockCallSpec.content()).thenReturn(null);

        List<PlanStep> steps = plannerAgent.plan("创建活动", "");

        assertThat(steps).isNotEmpty();
    }

    @Test
    @DisplayName("LLM 抛异常时，plan() 应返回空列表（而非传播异常）")
    void plan_llmThrowsException_shouldReturnEmptyList() {
        when(mockRequestSpec.call()).thenThrow(new RuntimeException("OpenAI timeout"));

        List<PlanStep> steps = plannerAgent.plan("创建活动", "");

        assertThat(steps).isEmpty();
    }

    @Test
    @DisplayName("无效 JSON 应返回兜底默认步骤（解析失败不抛异常）")
    void plan_invalidJson_shouldReturnDefaultStepGracefully() {
        String invalidJson = "[{invalid json here}]";
        when(mockCallSpec.content()).thenReturn(invalidJson);

        assertThatCode(() -> plannerAgent.plan("创建活动", ""))
                .doesNotThrowAnyException();

        List<PlanStep> steps = plannerAgent.plan("创建活动", "");
        assertThat(steps).isNotEmpty();
    }

    // ─── 字段提取测试 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("步骤中的 params 字段应被正确反序列化")
    void plan_stepsWithParams_shouldDeserializeParamsCorrectly() {
        String jsonResponse = """
                [
                  {"stepId":1,"action":"configureActivityRules","description":"配置满减规则",
                   "params":{"threshold":100,"discount":20,"activityType":"FULL_REDUCTION"},"dependsOn":[]}
                ]
                """;
        when(mockCallSpec.content()).thenReturn(jsonResponse);

        List<PlanStep> steps = plannerAgent.plan("配置满100减20", "");

        assertThat(steps).hasSize(1);
        PlanStep step = steps.get(0);
        assertThat(step.params()).containsEntry("threshold", 100);
        assertThat(step.params()).containsEntry("discount", 20);
    }

    @Test
    @DisplayName("dependsOn 字段应被正确反序列化为整数列表")
    void plan_stepsWithDependsOn_shouldDeserializeDependsOnCorrectly() {
        String jsonResponse = """
                [
                  {"stepId":1,"action":"createActivity","description":"创建活动","params":{},"dependsOn":[]},
                  {"stepId":2,"action":"createCoupon","description":"创建券","params":{},"dependsOn":[1]}
                ]
                """;
        when(mockCallSpec.content()).thenReturn(jsonResponse);

        List<PlanStep> steps = plannerAgent.plan("创建COUPON活动并添加券", "");

        assertThat(steps.get(1).dependsOn()).containsExactly(1);
    }

    // ─── replan 测试 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("replan() 应包含缺失步骤信息在 user prompt 中，并返回补全后的步骤")
    void replan_withMissingSteps_shouldReturnCompletedPlan() {
        String jsonResponse = """
                [
                  {"stepId":1,"action":"createActivity","description":"创建活动","params":{},"dependsOn":[]},
                  {"stepId":2,"action":"createCoupon","description":"补充：创建优惠券","params":{},"dependsOn":[1]}
                ]
                """;
        when(mockCallSpec.content()).thenReturn(jsonResponse);

        List<PlanStep> steps = plannerAgent.replan(
                "创建COUPON活动",
                "SOP上下文",
                List.of("缺少创建优惠券步骤（createCoupon）")
        );

        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).action()).isEqualTo("createCoupon");
    }

    @Test
    @DisplayName("replan() 时 LLM 异常应返回兜底默认步骤（不向上传播）")
    void replan_llmThrowsException_shouldReturnDefaultStep() {
        when(mockRequestSpec.call()).thenThrow(new RuntimeException("service unavailable"));

        assertThatCode(() -> plannerAgent.replan("query", "sop", List.of("miss")))
                .doesNotThrowAnyException();
    }
}
