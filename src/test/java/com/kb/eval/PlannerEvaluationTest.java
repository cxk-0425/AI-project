package com.kb.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.agent.PlannerAgent;
import com.kb.agent.PlanStep;
import com.kb.eval.util.EvalDatasetLoader;
import com.kb.eval.util.EvalMetricsCalculator;
import com.kb.eval.util.EvalReportPrinter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planner Agent 评测测试
 * <p>
 * 衡量 {@link PlannerAgent} 生成执行计划的质量：
 * <ul>
 *   <li>步骤完整率：关键 action 均被包含的比例</li>
 *   <li>工具匹配准确率：action 映射到正确 MCP 工具的比例</li>
 *   <li>参数提取率：从 query 中正确提取参数的比例</li>
 *   <li>JSON 解析成功率：LLM 输出可被正确解析为步骤列表</li>
 *   <li>最少步骤数：步骤数 >= required_steps_count_min 的比例</li>
 * </ul>
 * <p>
 * 运行要求：需要配置 OPENAI_API_KEY 环境变量。
 * <p>
 * 运行命令：
 * <pre>
 *   mvn test -Dgroups="agent-eval" -Dtest="PlannerEvaluationTest"
 * </pre>
 */
@Tag("eval")
@Tag("agent-eval")
@SpringBootTest
class PlannerEvaluationTest {

    @Autowired
    private PlannerAgent plannerAgent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── 数据集结构 ──────────────────────────────────────────────────────────

    record PlannerTestCase(
            @JsonProperty("query") String query,
            @JsonProperty("sopContext") String sopContext,
            @JsonProperty("expected_actions") List<String> expectedActions,
            @JsonProperty("expected_params") Map<String, List<String>> expectedParams,
            @JsonProperty("required_steps_count_min") int requiredStepsCountMin,
            @JsonProperty("description") String description
    ) {}

    /** 单条评测结果记录 */
    record PlannerEvalRow(PlannerTestCase testCase, List<PlanStep> steps, long latencyMs,
                          boolean parseSuccess, double stepCompleteness, double toolMatchRate,
                          double paramExtractionRate) {}

    // ─── 主评测 ──────────────────────────────────────────────────────────────

    @Test
    void evaluatePlannerQuality() throws IOException {
        List<PlannerTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/planner_dataset.json", PlannerTestCase.class);

        assertThat(dataset).as("Planner 评测数据集不能为空").isNotEmpty();
        System.out.printf("%n【Planner Agent 评测】开始，共 %d 条用例%n", dataset.size());

        // ── 1. 批量推理 ────────────────────────────────────────────────────────
        List<PlannerEvalRow> rows = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (PlannerTestCase tc : dataset) {
            String sopContext = tc.sopContext() != null ? tc.sopContext() : "";

            long start = System.currentTimeMillis();
            List<PlanStep> steps = plannerAgent.plan(tc.query(), sopContext);
            long latency = System.currentTimeMillis() - start;
            latencies.add(latency);

            boolean parseSuccess = steps != null && !steps.isEmpty();
            double stepCompleteness = computeStepCompleteness(steps, tc.expectedActions());
            double toolMatchRate = computeToolMatchRate(steps, tc.expectedActions());
            double paramExtractionRate = computeParamExtractionRate(steps, tc.expectedParams());

            rows.add(new PlannerEvalRow(tc, steps != null ? steps : List.of(), latency,
                    parseSuccess, stepCompleteness, toolMatchRate, paramExtractionRate));
        }

        // ── 2. 聚合指标 ────────────────────────────────────────────────────────
        double avgStepCompleteness = rows.stream().mapToDouble(r -> r.stepCompleteness()).average().orElse(0.0);
        double avgToolMatchRate = rows.stream().mapToDouble(r -> r.toolMatchRate()).average().orElse(0.0);
        double avgParamExtractionRate = rows.stream().mapToDouble(r -> r.paramExtractionRate()).average().orElse(0.0);
        double jsonParseSuccessRate = rows.stream().filter(r -> r.parseSuccess()).count() / (double) rows.size();
        double avgLatencyMs = latencies.stream().mapToLong(l -> l).average().orElse(0.0);

        // ── 3. 打印报告 ────────────────────────────────────────────────────────
        EvalReportPrinter.printPlannerReport(
                avgStepCompleteness, avgToolMatchRate, avgParamExtractionRate,
                jsonParseSuccessRate, avgLatencyMs, rows.size());

        // 打印失败用例
        List<String> failedCases = rows.stream()
                .filter(r -> r.stepCompleteness() < 1.0 || r.toolMatchRate() < 1.0)
                .map(r -> String.format("[%s] 完整性=%.2f 工具匹配=%.2f 步骤=%s",
                        r.testCase().description(), r.stepCompleteness(), r.toolMatchRate(),
                        r.steps().stream().map(PlanStep::action).collect(Collectors.joining(","))))
                .collect(Collectors.toList());
        EvalReportPrinter.printFailedCases(failedCases);

        // ── 4. 断言 ────────────────────────────────────────────────────────────
        assertThat(jsonParseSuccessRate)
                .as("JSON 解析成功率应 >= 98%%，实际: %.4f", jsonParseSuccessRate)
                .isGreaterThanOrEqualTo(0.98);

        assertThat(avgToolMatchRate)
                .as("工具匹配准确率应 >= 90%%，实际: %.4f", avgToolMatchRate)
                .isGreaterThanOrEqualTo(0.90);

        assertThat(avgStepCompleteness)
                .as("步骤完整率应 >= 85%%，实际: %.4f", avgStepCompleteness)
                .isGreaterThanOrEqualTo(0.85);

        assertThat(avgLatencyMs)
                .as("平均规划耗时应 <= 5000ms，实际: %.1f ms", avgLatencyMs)
                .isLessThanOrEqualTo(5000.0);
    }

    // ─── 指标计算方法 ────────────────────────────────────────────────────────

    /**
     * 步骤完整率：expected actions 中有多少出现在实际步骤中
     */
    private double computeStepCompleteness(List<PlanStep> steps, List<String> expectedActions) {
        if (expectedActions == null || expectedActions.isEmpty()) return 1.0;
        if (steps == null || steps.isEmpty()) return 0.0;

        Set<String> actualActions = steps.stream().map(PlanStep::action).collect(Collectors.toSet());
        long matched = expectedActions.stream().filter(actualActions::contains).count();
        return (double) matched / expectedActions.size();
    }

    /**
     * 工具匹配准确率：实际步骤中有多少 action 属于 expected actions 集合
     */
    private double computeToolMatchRate(List<PlanStep> steps, List<String> expectedActions) {
        if (steps == null || steps.isEmpty()) return 0.0;
        if (expectedActions == null || expectedActions.isEmpty()) return 1.0;

        Set<String> expectedSet = new HashSet<>(expectedActions);
        long matched = steps.stream().filter(s -> expectedSet.contains(s.action())).count();
        return (double) matched / steps.size();
    }

    /**
     * 参数提取率：对 expectedParams 中的每个 action，检查 step.params 中是否包含该参数 key
     */
    private double computeParamExtractionRate(List<PlanStep> steps,
                                               Map<String, List<String>> expectedParams) {
        if (expectedParams == null || expectedParams.isEmpty()) return 1.0;
        if (steps == null || steps.isEmpty()) return 0.0;

        Map<String, Map<Object, Object>> stepParamMap = new HashMap<>();
        for (PlanStep step : steps) {
            if (step.params() != null) {
                stepParamMap.put(step.action(), new HashMap<>(step.params()));
            }
        }

        int total = 0;
        int matched = 0;
        for (Map.Entry<String, List<String>> entry : expectedParams.entrySet()) {
            String action = entry.getKey();
            List<String> expectedKeys = entry.getValue();
            Map<?, ?> actualParams = stepParamMap.get(action);

            for (String key : expectedKeys) {
                total++;
                if (actualParams != null && actualParams.containsKey(key)) {
                    matched++;
                }
            }
        }

        return total == 0 ? 1.0 : (double) matched / total;
    }
}
