package com.kb.eval;

import com.kb.agent.ConfigAgentOrchestrator;
import com.kb.mcp.MarketingApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Agent 端到端评测测试
 * <p>
 * 评测完整 Agent 链路（SOP RAG → Planner → Supervisor → Skill）：
 * <ul>
 *   <li>SSE 事件完整性：是否收到全部预期事件类型</li>
 *   <li>执行成功率：所有步骤全部执行成功的比例</li>
 *   <li>重规划触发率：Supervisor 触发 replan 的频率</li>
 *   <li>端到端延迟</li>
 * </ul>
 * <p>
 * 使用 {@link MockBean} 替换 {@link MarketingApiClient}，避免依赖真实营销 API 服务。
 * <p>
 * 运行命令：
 * <pre>
 *   mvn test -Dgroups="agent-eval" -Dtest="AgentE2EEvaluationTest"
 * </pre>
 */
@Tag("eval")
@Tag("agent-eval")
@SpringBootTest
@DisplayName("Agent 端到端评测")
class AgentE2EEvaluationTest {

    @Autowired
    private ConfigAgentOrchestrator orchestrator;

    @MockBean
    private MarketingApiClient marketingApiClient;

    // ─── 测试用例定义 ────────────────────────────────────────────────────────

    private static final List<String> EVAL_QUERIES = List.of(
            "帮我创建一个双十一满减活动，满100减20",
            "创建COUPON类型的优惠券活动并发放10张满50减10的券",
            "查询所有活动状态并将未上线的活动全部上线",
            "新建一个618折扣活动，9折优惠，明天开始",
            "修改活动ACT_001的满减门槛从满100改为满150减30"
    );

    private static final List<String> EXPECTED_EVENT_TYPES = List.of(
            "agent-status", "sop-sources", "plan", "step-start", "step-result", "agent-done"
    );

    // ─── 单条测试执行 ────────────────────────────────────────────────────────

    @BeforeEach
    void setUpMocks() {
        // Mock 所有 MarketingApiClient 调用，返回成功响应
        when(marketingApiClient.createActivity(any()))
                .thenReturn("{\"success\":true,\"id\":\"ACT_MOCK_001\",\"message\":\"活动创建成功\"}");
        when(marketingApiClient.listActivities(any()))
                .thenReturn("{\"success\":true,\"data\":[{\"id\":\"ACT_001\",\"status\":\"DRAFT\"}]}");
        when(marketingApiClient.getActivityDetail(anyString()))
                .thenReturn("{\"success\":true,\"data\":{\"id\":\"ACT_001\",\"status\":\"ACTIVE\"}}");
        when(marketingApiClient.updateActivity(anyString(), any()))
                .thenReturn("{\"success\":true,\"message\":\"活动更新成功\"}");
        when(marketingApiClient.toggleActivityStatus(anyString(), anyString()))
                .thenReturn("{\"success\":true,\"message\":\"活动状态变更成功\"}");
        when(marketingApiClient.createCoupon(any()))
                .thenReturn("{\"success\":true,\"couponId\":\"COUP_001\",\"message\":\"优惠券创建成功\"}");
        when(marketingApiClient.configureActivityRules(anyString(), any()))
                .thenReturn("{\"success\":true,\"message\":\"活动规则配置成功\"}");
    }

    // ─── 主评测：SSE 事件完整性 ────────────────────────────────────────────────

    @Test
    @DisplayName("评测 Agent 链路 SSE 事件完整性")
    void evaluateSseEventCompleteness() throws InterruptedException {
        System.out.printf("%n【Agent 端到端评测】开始，共 %d 条用例%n", EVAL_QUERIES.size());
        System.out.println("=".repeat(70));

        List<EvalCaseResult> results = new ArrayList<>();

        for (String query : EVAL_QUERIES) {
            EvalCaseResult result = runSingleCase(query);
            results.add(result);
            printCaseResult(query, result);
        }

        // ── 汇总指标 ──────────────────────────────────────────────────────────
        System.out.println("\n" + "=".repeat(70));
        System.out.println("【汇总指标】");

        // SSE 事件完整性
        long completeCases = results.stream().filter(r -> r.hasAllExpectedEvents()).count();
        double sseCompleteness = (double) completeCases / results.size();

        // step-result 成功率（success=true 的比例）
        double avgStepSuccessRate = results.stream()
                .mapToDouble(r -> r.stepSuccessRate())
                .average()
                .orElse(0.0);

        // 重规划触发率
        long replanCases = results.stream().filter(r -> r.hasReplan()).count();
        double replanRate = (double) replanCases / results.size();

        // 端到端延迟
        double avgLatencyMs = results.stream().mapToDouble(r -> r.latencyMs).average().orElse(0.0);

        System.out.printf("  %-28s %.4f  %s%n", "SSE 事件完整性",
                sseCompleteness, passOrFail(sseCompleteness >= 0.95));
        System.out.printf("  %-28s %.4f  %s%n", "步骤执行成功率",
                avgStepSuccessRate, passOrFail(avgStepSuccessRate >= 0.80));
        System.out.printf("  %-28s %.4f%n", "重规划触发率", replanRate);
        System.out.printf("  %-28s %.1f ms%n", "平均端到端延迟", avgLatencyMs);
        System.out.println("=".repeat(70));

        // ── 断言 ──────────────────────────────────────────────────────────────
        assertThat(sseCompleteness)
                .as("SSE 事件完整性应 >= 95%%，实际: %.4f", sseCompleteness)
                .isGreaterThanOrEqualTo(0.95);

        assertThat(avgStepSuccessRate)
                .as("步骤执行成功率应 >= 80%%，实际: %.4f", avgStepSuccessRate)
                .isGreaterThanOrEqualTo(0.80);
    }

    // ─── 单条用例运行 ─────────────────────────────────────────────────────────

    private EvalCaseResult runSingleCase(String query) throws InterruptedException {
        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        List<Boolean> stepSuccessFlags = new CopyOnWriteArrayList<>();
        List<Boolean> replanFlags = new CopyOnWriteArrayList<>();

        CountDownLatch latch = new CountDownLatch(1);
        long startTime = System.currentTimeMillis();

        // 创建 Mock SseEmitter，捕获发出的所有事件
        SseEmitter emitter = new SseEmitter(30_000L) {
            @Override
            public void send(SseEventBuilder eventBuilder) throws IOException {
                // 解析事件类型
                String eventData = eventBuilder.build().toString();
                if (eventData.contains("event:")) {
                    String eventType = extractEventType(eventData);
                    receivedEventTypes.add(eventType);

                    // 检测重规划事件
                    if ("agent-status".equals(eventType) && eventData.contains("replan")) {
                        replanFlags.add(true);
                    }

                    // 从 step-result 中提取成功/失败
                    if ("step-result".equals(eventType)) {
                        stepSuccessFlags.add(eventData.contains("\"success\":true"));
                    }
                }
            }

            @Override
            public void complete() {
                latch.countDown();
            }

            @Override
            public void completeWithError(Throwable ex) {
                latch.countDown();
            }
        };

        // 异步执行 Agent 链路
        Thread agentThread = Thread.ofVirtual().start(() ->
                orchestrator.execute(query, emitter));

        // 等待完成（最多 60 秒）
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        long latencyMs = System.currentTimeMillis() - startTime;

        return new EvalCaseResult(
                receivedEventTypes,
                stepSuccessFlags,
                !replanFlags.isEmpty(),
                latencyMs,
                completed
        );
    }

    // ─── 辅助方法 ────────────────────────────────────────────────────────────

    /**
     * 从 SSE 事件字符串中提取事件类型
     */
    private String extractEventType(String eventData) {
        // 格式：event:event-type
        int start = eventData.indexOf("event:");
        if (start < 0) return "unknown";
        int end = eventData.indexOf("\n", start);
        if (end < 0) end = eventData.length();
        return eventData.substring(start + 6, end).trim();
    }

    private void printCaseResult(String query, EvalCaseResult result) {
        System.out.printf("%n  查询: \"%s\"%n", query.substring(0, Math.min(query.length(), 40)));
        System.out.printf("  已收事件: %s%n", result.receivedEventTypes());
        System.out.printf("  步骤成功率: %.2f  重规划: %s  延迟: %d ms  完成: %s%n",
                result.stepSuccessRate(), result.hasReplan() ? "是" : "否",
                result.latencyMs, result.completed ? "✓" : "✗");
        System.out.printf("  事件完整性: %s%n", result.hasAllExpectedEvents() ? "✓ 通过" : "✗ 缺少事件");
    }

    private String passOrFail(boolean pass) {
        return pass ? "✓ PASS" : "✗ FAIL";
    }

    // ─── 数据结构 ─────────────────────────────────────────────────────────────

    record EvalCaseResult(
            List<String> receivedEventTypes,
            List<Boolean> stepSuccessFlags,
            boolean hasReplan,
            long latencyMs,
            boolean completed
    ) {
        /** 是否收到了所有预期事件类型 */
        boolean hasAllExpectedEvents() {
            return EXPECTED_EVENT_TYPES.stream().allMatch(receivedEventTypes::contains);
        }

        /** 步骤执行成功率 */
        double stepSuccessRate() {
            if (stepSuccessFlags.isEmpty()) return 1.0;
            long successes = stepSuccessFlags.stream().filter(b -> b).count();
            return (double) successes / stepSuccessFlags.size();
        }
    }
}
