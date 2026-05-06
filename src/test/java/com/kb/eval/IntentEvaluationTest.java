package com.kb.eval;

import com.kb.eval.util.EvalDatasetLoader;
import com.kb.eval.util.EvalMetricsCalculator;
import com.kb.eval.util.EvalReportPrinter;
import com.kb.eval.util.IntentTestCase;
import com.kb.intent.IntentResult;
import com.kb.intent.LlmIntentClassifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图识别评测测试
 * <p>
 * 使用人工标注数据集，对 {@link LlmIntentClassifier} 进行系统性评测。
 * 评测指标：Accuracy / Macro-F1 / ECE / 平均延迟 / 每类 Precision+Recall+F1。
 * <p>
 * 运行要求：
 * <ul>
 *   <li>需要配置 OPENAI_API_KEY 环境变量（或 application.yml 中的 spring.ai.openai.api-key）</li>
 *   <li>该测试会实际调用 LLM API，请注意 Token 费用</li>
 * </ul>
 * <p>
 * 运行命令：
 * <pre>
 *   mvn test -Dgroups="model-eval" -Dtest="IntentEvaluationTest" -Dspring.ai.openai.api-key=sk-xxx
 * </pre>
 */
@Tag("eval")
@Tag("model-eval")
@SpringBootTest
class IntentEvaluationTest {

    @Autowired
    private LlmIntentClassifier llmClassifier;

    /** 评测结果记录 */
    record EvalRow(IntentTestCase testCase, IntentResult result, long latencyMs) {}

    @Test
    void evaluateLlmIntentAccuracy() throws IOException {
        // ── 1. 加载数据集 ────────────────────────────────────────────────────
        List<IntentTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/intent_dataset.json", IntentTestCase.class);

        assertThat(dataset).as("意图识别数据集不能为空").isNotEmpty();
        System.out.printf("%n【意图识别评测】开始，共 %d 条样本%n", dataset.size());

        // ── 2. 批量推理 ──────────────────────────────────────────────────────
        List<EvalRow> rows = new ArrayList<>();
        for (IntentTestCase tc : dataset) {
            long start = System.currentTimeMillis();
            IntentResult result = llmClassifier.classify(tc.query());
            long latency = System.currentTimeMillis() - start;
            rows.add(new EvalRow(tc, result, latency));
        }

        // ── 3. 收集预测/真实标签 ─────────────────────────────────────────────
        List<String> predicted = rows.stream().map(r -> r.result().getType().name()).toList();
        List<String> actual = rows.stream().map(r -> r.testCase().expected()).toList();
        List<Double> confidences = rows.stream().map(r -> r.result().getConfidence()).toList();
        List<Boolean> corrects = new ArrayList<>();
        for (int i = 0; i < predicted.size(); i++) {
            corrects.add(predicted.get(i).equals(actual.get(i)));
        }

        // ── 4. 计算指标 ──────────────────────────────────────────────────────
        double accuracy = EvalMetricsCalculator.accuracy(predicted, actual);
        double macroF1 = EvalMetricsCalculator.macroF1(predicted, actual);
        double ece = EvalMetricsCalculator.ece(confidences, corrects, 10);
        double avgLatency = EvalMetricsCalculator.mean(
                rows.stream().map(r -> (double) r.latencyMs()).toList());

        Map<String, Double> precMap = EvalMetricsCalculator.precisionPerClass(predicted, actual);
        Map<String, Double> recMap = EvalMetricsCalculator.recallPerClass(predicted, actual);
        Map<String, double[]> perClassMetrics = new LinkedHashMap<>();
        for (String label : precMap.keySet()) {
            double p = precMap.getOrDefault(label, 0.0);
            double r = recMap.getOrDefault(label, 0.0);
            double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
            perClassMetrics.put(label, new double[]{p, r, f1});
        }

        long correctCount = corrects.stream().filter(b -> b).count();

        // ── 5. 打印评测报告 ──────────────────────────────────────────────────
        EvalReportPrinter.printIntentReport(
                accuracy, macroF1, ece, avgLatency,
                perClassMetrics, dataset.size(), (int) correctCount);

        // 打印失败用例
        List<String> failedCases = new ArrayList<>();
        for (EvalRow row : rows) {
            if (!row.result().getType().name().equals(row.testCase().expected())) {
                failedCases.add(String.format("query=\"%s\" | expected=%s | got=%s (conf=%.2f)",
                        row.testCase().query(), row.testCase().expected(),
                        row.result().getType().name(), row.result().getConfidence()));
            }
        }
        EvalReportPrinter.printFailedCases(failedCases);

        // ── 6. 断言目标指标 ──────────────────────────────────────────────────
        assertThat(accuracy)
                .as("Accuracy 应 >= 90%%，实际: %.4f", accuracy)
                .isGreaterThanOrEqualTo(0.90);

        assertThat(macroF1)
                .as("Macro F1 应 >= 88%%，实际: %.4f", macroF1)
                .isGreaterThanOrEqualTo(0.88);

        assertThat(ece)
                .as("ECE 应 <= 0.10，实际: %.4f", ece)
                .isLessThanOrEqualTo(0.10);

        assertThat(avgLatency)
                .as("平均延迟应 <= 5000ms，实际: %.1f ms", avgLatency)
                .isLessThanOrEqualTo(5000.0);
    }

    /**
     * BERT 意图分类器的评测（若 BERT 服务可用时使用）
     * <p>
     * 本测试默认跳过（假设本地开发 BERT 服务未启动）。
     * 需要手动设置 bert.enabled=true 并启动 BERT FastAPI 服务后运行。
     */
    @Test
    void evaluateBertIntentAccuracy() throws IOException {
        // 通过 bert.enabled=true 环境变量开启 BERT 评测
        String bertEnabled = System.getProperty("bert.enabled",
                System.getenv().getOrDefault("BERT_ENABLED", "false"));

        if (!"true".equals(bertEnabled)) {
            System.out.println("[跳过] BERT 评测需设置 bert.enabled=true，当前已跳过。");
            return;
        }

        List<IntentTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/intent_dataset.json", IntentTestCase.class);

        // TODO: 注入 BertIntentClassifier 后在此执行完整评测
        System.out.printf("BERT 评测数据集加载成功，共 %d 条样本，请实现评测逻辑。%n", dataset.size());
    }

    /**
     * 置信度分布分析（调试用）——展示置信度直方图
     */
    @Test
    void analyzeConfidenceDistribution() throws IOException {
        List<IntentTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/intent_dataset.json", IntentTestCase.class);

        System.out.println("\n【置信度分布分析】");
        System.out.println("区间          | 样本数");
        System.out.println("-".repeat(30));

        int[] buckets = new int[10]; // [0.0,0.1), [0.1,0.2), ...
        for (IntentTestCase tc : dataset) {
            IntentResult result = llmClassifier.classify(tc.query());
            int bucket = Math.min((int) (result.getConfidence() * 10), 9);
            buckets[bucket]++;
        }

        for (int i = 0; i < buckets.length; i++) {
            System.out.printf("[%.1f, %.1f)  | %d%n",
                    i * 0.1, (i + 1) * 0.1, buckets[i]);
        }
    }
}
