package com.kb.eval.util;

import java.util.*;

/**
 * 评测报告输出工具
 * <p>
 * 以控制台表格形式输出评测结果，便于查看和对比。
 */
public class EvalReportPrinter {

    private static final String LINE = "=".repeat(70);
    private static final String DIVIDER = "-".repeat(70);

    /**
     * 打印章节标题
     */
    public static void printHeader(String title) {
        System.out.println("\n" + LINE);
        System.out.println("  " + title);
        System.out.println(LINE);
    }

    /**
     * 打印意图识别评测报告
     *
     * @param accuracy          总体准确率
     * @param macroF1           宏平均 F1
     * @param ece               期望校准误差
     * @param avgLatencyMs      平均延迟（ms）
     * @param perClassMetrics   每个类别的 precision/recall/f1
     * @param totalSamples      总样本数
     * @param correctSamples    正确样本数
     */
    public static void printIntentReport(
            double accuracy,
            double macroF1,
            double ece,
            double avgLatencyMs,
            Map<String, double[]> perClassMetrics,  // label -> [precision, recall, f1]
            int totalSamples,
            int correctSamples) {

        printHeader("意图识别评测报告");
        System.out.printf("  总样本数:   %d%n", totalSamples);
        System.out.printf("  正确预测:   %d%n", correctSamples);
        System.out.println(DIVIDER);
        System.out.printf("  %-20s %s%n", "指标", "值");
        System.out.println(DIVIDER);
        System.out.printf("  %-20s %.4f  %s%n", "Accuracy", accuracy, passOrFail(accuracy >= 0.90));
        System.out.printf("  %-20s %.4f  %s%n", "Macro F1", macroF1, passOrFail(macroF1 >= 0.88));
        System.out.printf("  %-20s %.4f  %s%n", "ECE", ece, passOrFail(ece <= 0.10));
        System.out.printf("  %-20s %.1f ms%n", "Avg Latency", avgLatencyMs);
        System.out.println(DIVIDER);
        System.out.printf("  %-12s %-12s %-12s %-12s%n", "Class", "Precision", "Recall", "F1");
        System.out.println(DIVIDER);
        for (Map.Entry<String, double[]> entry : perClassMetrics.entrySet()) {
            double[] m = entry.getValue();
            System.out.printf("  %-12s %-12.4f %-12.4f %-12.4f%n",
                    entry.getKey(), m[0], m[1], m[2]);
        }
        System.out.println(LINE);
    }

    /**
     * 打印 RAG 检索评测报告
     *
     * @param recallAtK     Recall@K
     * @param precisionAtK  Precision@K
     * @param ndcgAtK       NDCG@K
     * @param mrr           MRR
     * @param k             K 值
     * @param totalQueries  查询总数
     * @param hybridVsVector 混合检索 vs 纯向量 NDCG 对比（可为 null）
     */
    public static void printRagReport(
            double recallAtK,
            double precisionAtK,
            double ndcgAtK,
            double mrr,
            int k,
            int totalQueries,
            Double hybridNdcg,
            Double vectorNdcg) {

        printHeader("RAG 检索质量评测报告");
        System.out.printf("  查询总数: %d，K = %d%n", totalQueries, k);
        System.out.println(DIVIDER);
        System.out.printf("  %-20s %s%n", "指标", "值");
        System.out.println(DIVIDER);
        System.out.printf("  %-20s %.4f  %s%n",
                "Recall@" + k, recallAtK, passOrFail(recallAtK >= 0.75));
        System.out.printf("  %-20s %.4f  %s%n",
                "Precision@" + k, precisionAtK, passOrFail(precisionAtK >= 0.70));
        System.out.printf("  %-20s %.4f  %s%n",
                "NDCG@" + k, ndcgAtK, passOrFail(ndcgAtK >= 0.72));
        System.out.printf("  %-20s %.4f  %s%n",
                "MRR", mrr, passOrFail(mrr >= 0.65));

        if (hybridNdcg != null && vectorNdcg != null) {
            double improvement = (hybridNdcg - vectorNdcg) / vectorNdcg * 100;
            System.out.println(DIVIDER);
            System.out.printf("  混合检索 NDCG@%d:   %.4f%n", k, hybridNdcg);
            System.out.printf("  纯向量 NDCG@%d:    %.4f%n", k, vectorNdcg);
            System.out.printf("  混合提升率:          %.1f%%  %s%n",
                    improvement, passOrFail(improvement >= 5.0));
        }
        System.out.println(LINE);
    }

    /**
     * 打印向量缓存评测报告
     */
    public static void printCacheReport(
            double legalHitRate,
            double falseHitRate,
            int positivePairs,
            int negativePairs) {

        printHeader("向量缓存评测报告");
        System.out.printf("  正样本对（应命中）: %d%n", positivePairs);
        System.out.printf("  负样本对（不应命中）: %d%n", negativePairs);
        System.out.println(DIVIDER);
        System.out.printf("  %-20s %.4f  %s%n", "合法命中率", legalHitRate, passOrFail(legalHitRate >= 0.95));
        System.out.printf("  %-20s %.4f  %s%n", "误命中率", falseHitRate, passOrFail(falseHitRate <= 0.02));
        System.out.println(LINE);
    }

    /**
     * 打印 Planner Agent 评测报告
     */
    public static void printPlannerReport(
            double stepCompletenessRate,
            double toolMatchRate,
            double paramExtractionRate,
            double jsonParseSuccessRate,
            double avgLatencyMs,
            int totalCases) {

        printHeader("Planner Agent 评测报告");
        System.out.printf("  评测用例数: %d%n", totalCases);
        System.out.println(DIVIDER);
        System.out.printf("  %-24s %.4f  %s%n", "步骤完整率",
                stepCompletenessRate, passOrFail(stepCompletenessRate >= 0.85));
        System.out.printf("  %-24s %.4f  %s%n", "工具匹配准确率",
                toolMatchRate, passOrFail(toolMatchRate >= 0.90));
        System.out.printf("  %-24s %.4f  %s%n", "参数提取率",
                paramExtractionRate, passOrFail(paramExtractionRate >= 0.80));
        System.out.printf("  %-24s %.4f  %s%n", "JSON 解析成功率",
                jsonParseSuccessRate, passOrFail(jsonParseSuccessRate >= 0.98));
        System.out.printf("  %-24s %.1f ms%n", "平均规划耗时", avgLatencyMs);
        System.out.println(LINE);
    }

    /**
     * 打印 Supervisor Agent 评测报告
     */
    public static void printSupervisorReport(
            double truePassRate,
            double missingDetectionRate,
            double falsePositiveRate,
            double avgLatencyMs,
            int totalCases,
            int completeCases,
            int incompleteCases) {

        printHeader("Supervisor Agent 评测报告");
        System.out.printf("  完整计划用例: %d，不完整计划用例: %d%n", completeCases, incompleteCases);
        System.out.println(DIVIDER);
        System.out.printf("  %-24s %.4f  %s%n", "正确放行率（TruePass）",
                truePassRate, passOrFail(truePassRate >= 0.90));
        System.out.printf("  %-24s %.4f  %s%n", "缺失检出率",
                missingDetectionRate, passOrFail(missingDetectionRate >= 0.85));
        System.out.printf("  %-24s %.4f  %s%n", "误报率（FalsePositive）",
                falsePositiveRate, passOrFail(falsePositiveRate <= 0.15));
        System.out.printf("  %-24s %.1f ms%n", "平均校验耗时", avgLatencyMs);
        System.out.println(LINE);
    }

    /**
     * 打印错误用例（评测 debug 用）
     */
    public static void printFailedCases(List<String> failedDescriptions) {
        if (failedDescriptions.isEmpty()) return;
        System.out.println("\n【未通过用例（前10条）】");
        failedDescriptions.stream().limit(10)
                .forEach(d -> System.out.println("  ✗ " + d));
    }

    private static String passOrFail(boolean pass) {
        return pass ? "✓ PASS" : "✗ FAIL";
    }
}
