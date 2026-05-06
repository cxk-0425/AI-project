package com.kb.eval.util;

import java.util.*;

/**
 * 评测指标计算工具
 * <p>
 * 提供常用 NLP 评测指标的计算方法：
 * <ul>
 *   <li>Accuracy / Precision / Recall / F1（分类任务）</li>
 *   <li>NDCG@K / Recall@K / Precision@K / MRR（检索任务）</li>
 *   <li>ECE（置信度校准误差）</li>
 * </ul>
 */
public class EvalMetricsCalculator {

    // ─── 分类评测指标 ──────────────────────────────────────────────────────────

    /**
     * 计算总体准确率
     */
    public static double accuracy(List<String> predicted, List<String> actual) {
        if (predicted.size() != actual.size()) {
            throw new IllegalArgumentException("预测值和真实值长度不一致");
        }
        long correct = 0;
        for (int i = 0; i < predicted.size(); i++) {
            if (predicted.get(i).equals(actual.get(i))) correct++;
        }
        return (double) correct / predicted.size();
    }

    /**
     * 计算每个类别的 Precision
     *
     * @return Map&lt;label, precision&gt;
     */
    public static Map<String, Double> precisionPerClass(List<String> predicted, List<String> actual) {
        Set<String> labels = new HashSet<>(actual);
        Map<String, Double> result = new LinkedHashMap<>();
        for (String label : labels) {
            long tp = 0, fp = 0;
            for (int i = 0; i < predicted.size(); i++) {
                if (predicted.get(i).equals(label)) {
                    if (actual.get(i).equals(label)) tp++;
                    else fp++;
                }
            }
            result.put(label, (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp));
        }
        return result;
    }

    /**
     * 计算每个类别的 Recall
     *
     * @return Map&lt;label, recall&gt;
     */
    public static Map<String, Double> recallPerClass(List<String> predicted, List<String> actual) {
        Set<String> labels = new HashSet<>(actual);
        Map<String, Double> result = new LinkedHashMap<>();
        for (String label : labels) {
            long tp = 0, fn = 0;
            for (int i = 0; i < predicted.size(); i++) {
                if (actual.get(i).equals(label)) {
                    if (predicted.get(i).equals(label)) tp++;
                    else fn++;
                }
            }
            result.put(label, (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn));
        }
        return result;
    }

    /**
     * 计算 Macro F1（各类别 F1 的算术平均）
     */
    public static double macroF1(List<String> predicted, List<String> actual) {
        Map<String, Double> precisions = precisionPerClass(predicted, actual);
        Map<String, Double> recalls = recallPerClass(predicted, actual);
        Set<String> labels = precisions.keySet();

        double f1Sum = 0;
        for (String label : labels) {
            double p = precisions.get(label);
            double r = recalls.get(label);
            double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
            f1Sum += f1;
        }
        return labels.isEmpty() ? 0.0 : f1Sum / labels.size();
    }

    /**
     * 计算期望校准误差（Expected Calibration Error，ECE）
     * <p>
     * 将置信度按 bucketCount 个桶分组，计算每个桶内平均置信度与实际准确率的加权差值。
     *
     * @param confidences  每条样本的预测置信度（0-1）
     * @param correctFlags 每条样本是否预测正确
     * @param bucketCount  桶数量（通常 10）
     * @return ECE 值（0-1，越小越好）
     */
    public static double ece(List<Double> confidences, List<Boolean> correctFlags, int bucketCount) {
        if (confidences.size() != correctFlags.size()) {
            throw new IllegalArgumentException("置信度和正确标记列表长度不一致");
        }

        int n = confidences.size();
        double[] bucketConf = new double[bucketCount];
        double[] bucketAcc = new double[bucketCount];
        int[] bucketCount_ = new int[bucketCount];

        for (int i = 0; i < n; i++) {
            int bucket = Math.min((int) (confidences.get(i) * bucketCount), bucketCount - 1);
            bucketConf[bucket] += confidences.get(i);
            bucketAcc[bucket] += correctFlags.get(i) ? 1.0 : 0.0;
            bucketCount_[bucket]++;
        }

        double ece = 0;
        for (int b = 0; b < bucketCount; b++) {
            if (bucketCount_[b] == 0) continue;
            double avgConf = bucketConf[b] / bucketCount_[b];
            double avgAcc = bucketAcc[b] / bucketCount_[b];
            ece += ((double) bucketCount_[b] / n) * Math.abs(avgConf - avgAcc);
        }
        return ece;
    }

    // ─── 检索评测指标 ──────────────────────────────────────────────────────────

    /**
     * 计算 Recall@K
     *
     * @param retrievedIds  检索返回的文档 ID 列表（已按相关度排序）
     * @param relevantIds   Ground Truth 相关文档 ID 集合
     * @param k             取前 K 个
     */
    public static double recallAtK(List<String> retrievedIds, Set<String> relevantIds, int k) {
        if (relevantIds.isEmpty()) return 0.0;
        long hits = retrievedIds.stream().limit(k).filter(relevantIds::contains).count();
        return (double) hits / relevantIds.size();
    }

    /**
     * 计算 Precision@K
     */
    public static double precisionAtK(List<String> retrievedIds, Set<String> relevantIds, int k) {
        if (k == 0) return 0.0;
        long hits = retrievedIds.stream().limit(k).filter(relevantIds::contains).count();
        return (double) hits / k;
    }

    /**
     * 计算 NDCG@K（使用 binary relevance）
     *
     * @param retrievedIds  检索返回的文档 ID 列表
     * @param relevanceMap  文档 ID -> 相关性等级（0=不相关, 1=部分相关, 2=高度相关）
     * @param k             取前 K 个
     */
    public static double ndcgAtK(List<String> retrievedIds, Map<String, Integer> relevanceMap, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, retrievedIds.size()); i++) {
            String id = retrievedIds.get(i);
            int rel = relevanceMap.getOrDefault(id, 0);
            dcg += rel / (Math.log(i + 2) / Math.log(2));  // log2(i+2)
        }

        // 计算 IDCG（理想 DCG）
        List<Integer> sortedRels = relevanceMap.values().stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        double idcg = 0;
        for (int i = 0; i < Math.min(k, sortedRels.size()); i++) {
            idcg += sortedRels.get(i) / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /**
     * 计算 MRR（Mean Reciprocal Rank）
     * <p>
     * 每个查询取第一个相关文档排名的倒数，再求均值。
     *
     * @param retrievedIdsList 多个查询的检索结果列表（外层 list 对应每个查询）
     * @param relevantIdsList  多个查询的相关文档 ID 集合
     */
    public static double mrr(List<List<String>> retrievedIdsList, List<Set<String>> relevantIdsList) {
        if (retrievedIdsList.size() != relevantIdsList.size()) {
            throw new IllegalArgumentException("查询列表长度不一致");
        }

        double sum = 0;
        for (int q = 0; q < retrievedIdsList.size(); q++) {
            List<String> retrieved = retrievedIdsList.get(q);
            Set<String> relevant = relevantIdsList.get(q);

            for (int i = 0; i < retrieved.size(); i++) {
                if (relevant.contains(retrieved.get(i))) {
                    sum += 1.0 / (i + 1);
                    break;
                }
            }
        }
        return retrievedIdsList.isEmpty() ? 0.0 : sum / retrievedIdsList.size();
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    /**
     * 计算平均值
     */
    public static double mean(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 计算标准差
     */
    public static double stdDev(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double mean = mean(values);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }
}
