package com.kb.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.agent.PlanStep;
import com.kb.agent.SupervisorAgent;
import com.kb.agent.ValidationResult;
import com.kb.eval.util.EvalDatasetLoader;
import com.kb.eval.util.EvalReportPrinter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supervisor Agent 评测测试
 * <p>
 * 衡量 {@link SupervisorAgent} 步骤校验的准确性：
 * <ul>
 *   <li><b>正确放行率（True Pass Rate）</b>：完整计划被判为通过的比例</li>
 *   <li><b>缺失检出率（Missing Detection Rate）</b>：不完整计划中缺失步骤被发现的比例</li>
 *   <li><b>误报率（False Positive Rate）</b>：完整计划被误判为不完整的比例</li>
 * </ul>
 * <p>
 * 运行命令：
 * <pre>
 *   mvn test -Dgroups="agent-eval" -Dtest="SupervisorEvaluationTest"
 * </pre>
 */
@Tag("eval")
@Tag("agent-eval")
@SpringBootTest
class SupervisorEvaluationTest {

    @Autowired
    private SupervisorAgent supervisorAgent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── 数据集结构 ──────────────────────────────────────────────────────────

    record SupervisorTestCase(
            @JsonProperty("query") String query,
            @JsonProperty("steps") List<Map<String, Object>> steps,
            @JsonProperty("expected_complete") boolean expectedComplete,
            @JsonProperty("expected_missing_keywords") List<String> expectedMissingKeywords,
            @JsonProperty("description") String description
    ) {}

    /** 单条评测结果记录 */
    record SupervisorEvalRow(SupervisorTestCase testCase, ValidationResult result, long latencyMs) {}

    // ─── 主评测 ──────────────────────────────────────────────────────────────

    @Test
    void evaluateSupervisorAccuracy() throws IOException {
        List<SupervisorTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/supervisor_dataset.json", SupervisorTestCase.class);

        assertThat(dataset).as("Supervisor 评测数据集不能为空").isNotEmpty();
        System.out.printf("%n【Supervisor Agent 评测】开始，共 %d 条用例%n", dataset.size());

        // ── 1. 批量执行 ────────────────────────────────────────────────────────
        List<SupervisorEvalRow> rows = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (SupervisorTestCase tc : dataset) {
            // 将 Map 结构转换为 PlanStep 列表
            List<PlanStep> planSteps = convertToSteps(tc.steps());

            long start = System.currentTimeMillis();
            ValidationResult result = supervisorAgent.validate(tc.query(), planSteps);
            long latency = System.currentTimeMillis() - start;
            latencies.add(latency);

            rows.add(new SupervisorEvalRow(tc, result, latency));
        }

        // ── 2. 分组：完整计划用例 / 不完整计划用例 ────────────────────────────
        List<SupervisorEvalRow> completeRows = rows.stream()
                .filter(r -> r.testCase().expectedComplete())
                .toList();
        List<SupervisorEvalRow> incompleteRows = rows.stream()
                .filter(r -> !r.testCase().expectedComplete())
                .toList();

        // ── 3. 计算指标 ────────────────────────────────────────────────────────

        // 正确放行率：完整计划中，Supervisor 判为通过的比例
        long truePassCount = completeRows.stream()
                .filter(r -> r.result().isComplete())
                .count();
        double truePassRate = completeRows.isEmpty() ? 0.0 :
                (double) truePassCount / completeRows.size();

        // 缺失检出率：不完整计划中，Supervisor 判为不通过的比例
        long missingDetectedCount = incompleteRows.stream()
                .filter(r -> !r.result().isComplete())
                .count();
        double missingDetectionRate = incompleteRows.isEmpty() ? 0.0 :
                (double) missingDetectedCount / incompleteRows.size();

        // 误报率：完整计划被判为不通过
        long falsePositiveCount = completeRows.size() - truePassCount;
        double falsePositiveRate = completeRows.isEmpty() ? 0.0 :
                (double) falsePositiveCount / completeRows.size();

        // 关键词检出率：对于 incompleteRows，检查 missingKeywords 是否出现在 result.missingSteps 中
        double keywordDetectionRate = computeKeywordDetectionRate(incompleteRows);

        double avgLatencyMs = latencies.stream().mapToLong(l -> l).average().orElse(0.0);

        // ── 4. 打印报告 ────────────────────────────────────────────────────────
        EvalReportPrinter.printSupervisorReport(
                truePassRate, missingDetectionRate, falsePositiveRate, avgLatencyMs,
                rows.size(), completeRows.size(), incompleteRows.size());

        System.out.printf("  %-24s %.4f%n", "关键词检出率", keywordDetectionRate);

        // 打印误判用例
        System.out.println("\n【完整计划被误判为不完整（误报案例）】");
        completeRows.stream()
                .filter(r -> !r.result().isComplete())
                .limit(5)
                .forEach(r -> System.out.printf("  ✗ [%s] missing=%s%n",
                        r.testCase().description(), r.result().missingSteps()));

        System.out.println("\n【不完整计划未被检出（漏报案例）】");
        incompleteRows.stream()
                .filter(r -> r.result().isComplete())
                .limit(5)
                .forEach(r -> System.out.printf("  ✗ [%s] expectedMissing=%s%n",
                        r.testCase().description(),
                        r.testCase().expectedMissingKeywords()));

        // ── 5. 断言 ────────────────────────────────────────────────────────────
        if (!completeRows.isEmpty()) {
            assertThat(truePassRate)
                    .as("正确放行率应 >= 90%%，实际: %.4f", truePassRate)
                    .isGreaterThanOrEqualTo(0.90);

            assertThat(falsePositiveRate)
                    .as("误报率应 <= 15%%，实际: %.4f", falsePositiveRate)
                    .isLessThanOrEqualTo(0.15);
        }

        if (!incompleteRows.isEmpty()) {
            assertThat(missingDetectionRate)
                    .as("缺失检出率应 >= 85%%，实际: %.4f", missingDetectionRate)
                    .isGreaterThanOrEqualTo(0.85);
        }

        assertThat(avgLatencyMs)
                .as("平均校验耗时应 <= 4000ms，实际: %.1f ms", avgLatencyMs)
                .isLessThanOrEqualTo(4000.0);
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    /**
     * 将 JSON Map 列表转换为 PlanStep 列表
     */
    @SuppressWarnings("unchecked")
    private List<PlanStep> convertToSteps(List<Map<String, Object>> stepMaps) {
        if (stepMaps == null) return List.of();
        List<PlanStep> result = new ArrayList<>();
        for (Map<String, Object> m : stepMaps) {
            int stepId = ((Number) m.getOrDefault("stepId", 0)).intValue();
            String action = (String) m.getOrDefault("action", "");
            String description = (String) m.getOrDefault("description", "");
            Map<Object, Object> params = (Map<Object, Object>) m.getOrDefault("params", Map.of());
            List<Integer> dependsOn = (List<Integer>) m.getOrDefault("dependsOn", List.of());
            result.add(new PlanStep(stepId, action, description, params, dependsOn));
        }
        return result;
    }

    /**
     * 计算关键词检出率：对于不完整计划，expected_missing_keywords 中的关键词
     * 出现在 result.missingSteps 文本中的比例
     */
    private double computeKeywordDetectionRate(List<SupervisorEvalRow> incompleteRows) {
        int total = 0;
        int detected = 0;

        for (SupervisorEvalRow row : incompleteRows) {
            List<String> expectedKeywords = row.testCase().expectedMissingKeywords();
            if (expectedKeywords == null || expectedKeywords.isEmpty()) continue;

            String missingStepsText = String.join(" ", row.result().missingSteps()).toLowerCase();

            for (String keyword : expectedKeywords) {
                total++;
                if (missingStepsText.contains(keyword.toLowerCase())) {
                    detected++;
                }
            }
        }

        return total == 0 ? 1.0 : (double) detected / total;
    }
}
