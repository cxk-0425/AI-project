package com.kb.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kb.eval.util.EvalDatasetLoader;
import com.kb.eval.util.EvalReportPrinter;
import com.kb.service.VectorCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量缓存误命中率评测测试
 * <p>
 * 验证语义缓存（{@link VectorCacheService}）的命中准确性：
 * <ul>
 *   <li><b>合法命中（Positive Pair）</b>：语义真正相同的两个问题，应命中缓存</li>
 *   <li><b>误命中（Negative Pair）</b>：语义不同但相似的问题，不应命中缓存</li>
 * </ul>
 * <p>
 * 运行要求：
 * <ul>
 *   <li>需要配置 OPENAI_API_KEY（用于实际生成向量）</li>
 *   <li>数据集来自 {@code eval/cache_eval_pairs.json}</li>
 * </ul>
 */
@Tag("eval")
@Tag("model-eval")
@SpringBootTest
@DisplayName("向量缓存误命中率评测")
class VectorCacheEvaluationTest {

    @Autowired
    private VectorCacheService cacheService;

    @BeforeEach
    void setUp() {
        // 清空缓存，确保每次评测都是干净状态
        cacheService.invalidateAll();
        // 使用标准配置：相似度阈值 0.95，TTL 30 分钟
        ReflectionTestUtils.setField(cacheService, "similarityThreshold", 0.95);
        ReflectionTestUtils.setField(cacheService, "ttlMinutes", 30);
    }

    // ─── 数据集结构 ──────────────────────────────────────────────────────────

    record CacheEvalPairs(
            @JsonProperty("positive_pairs") List<List<String>> positivePairs,
            @JsonProperty("negative_pairs") List<List<String>> negativePairs
    ) {}

    // ─── 主评测 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("评测向量缓存合法命中率和误命中率")
    void evaluateCacheHitAccuracy() throws IOException {
        CacheEvalPairs pairs = EvalDatasetLoader.loadObject(
                "eval/cache_eval_pairs.json", CacheEvalPairs.class);

        int posHits = 0, posMisses = 0;
        int negHits = 0, negMisses = 0;

        // ── 1. 正样本对：应命中 ──────────────────────────────────────────────
        System.out.println("\n【正样本对（应命中）测试】");
        for (List<String> pair : pairs.positivePairs()) {
            if (pair.size() < 2) continue;
            String q1 = pair.get(0);
            String q2 = pair.get(1);

            // 清空缓存，写入 q1
            cacheService.invalidateAll();
            String context = "模拟上下文：" + q1;
            cacheService.put(q1, context, List.of("test.md"));

            // 查询 q2，应命中
            Optional<com.kb.model.CachedResult> result = cacheService.findSimilar(q2);
            boolean hit = result.isPresent();
            if (hit) posHits++;
            else posMisses++;

            System.out.printf("  [%s] q1=\"%s\" → q2=\"%s\"%n",
                    hit ? "✓ 命中" : "✗ 未命中", q1, q2);
        }

        // ── 2. 负样本对：不应命中（验证 no false positive） ──────────────────
        System.out.println("\n【负样本对（不应命中）测试】");
        for (List<String> pair : pairs.negativePairs()) {
            if (pair.size() < 2) continue;
            String q1 = pair.get(0);
            String q2 = pair.get(1);

            cacheService.invalidateAll();
            cacheService.put(q1, "上下文：" + q1, List.of("test.md"));

            Optional<com.kb.model.CachedResult> result = cacheService.findSimilar(q2);
            boolean hit = result.isPresent();
            if (hit) negHits++;  // 误命中！
            else negMisses++;

            System.out.printf("  [%s] q1=\"%s\" → q2=\"%s\"%n",
                    !hit ? "✓ 正确未命中" : "✗ 误命中！", q1, q2);
        }

        // ── 3. 计算指标 ───────────────────────────────────────────────────────
        int posTotal = posHits + posMisses;
        int negTotal = negHits + negMisses;

        double legalHitRate = posTotal > 0 ? (double) posHits / posTotal : 0.0;
        double falseHitRate = negTotal > 0 ? (double) negHits / negTotal : 0.0;

        // ── 4. 打印报告 ───────────────────────────────────────────────────────
        EvalReportPrinter.printCacheReport(legalHitRate, falseHitRate, posTotal, negTotal);

        // ── 5. 断言 ───────────────────────────────────────────────────────────
        assertThat(legalHitRate)
                .as("合法命中率应 >= 95%%，实际: %.4f（%d/%d）", legalHitRate, posHits, posTotal)
                .isGreaterThanOrEqualTo(0.95);

        assertThat(falseHitRate)
                .as("误命中率应 <= 2%%，实际: %.4f（%d/%d）", falseHitRate, negHits, negTotal)
                .isLessThanOrEqualTo(0.02);
    }

    /**
     * 阈值灵敏度分析：测试不同相似度阈值（0.90-0.98）下的误命中率曲线
     * <p>
     * 输出一个表格，帮助选择最优阈值。
     */
    @Test
    @DisplayName("阈值灵敏度分析（0.90-0.98）")
    void analyzeThresholdSensitivity() throws IOException {
        CacheEvalPairs pairs = EvalDatasetLoader.loadObject(
                "eval/cache_eval_pairs.json", CacheEvalPairs.class);

        double[] thresholds = {0.90, 0.92, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98};

        System.out.println("\n【阈值灵敏度分析】");
        System.out.printf("  %-8s  %-12s  %-12s%n", "阈值", "合法命中率", "误命中率");
        System.out.println("  " + "-".repeat(36));

        for (double threshold : thresholds) {
            ReflectionTestUtils.setField(cacheService, "similarityThreshold", threshold);

            int posHits = 0, posTotal = 0;
            int negHits = 0, negTotal = 0;

            for (List<String> pair : pairs.positivePairs()) {
                if (pair.size() < 2) continue;
                cacheService.invalidateAll();
                cacheService.put(pair.get(0), "ctx", List.of());
                if (cacheService.findSimilar(pair.get(1)).isPresent()) posHits++;
                posTotal++;
            }

            for (List<String> pair : pairs.negativePairs()) {
                if (pair.size() < 2) continue;
                cacheService.invalidateAll();
                cacheService.put(pair.get(0), "ctx", List.of());
                if (cacheService.findSimilar(pair.get(1)).isPresent()) negHits++;
                negTotal++;
            }

            double legalRate = posTotal > 0 ? (double) posHits / posTotal : 0;
            double falseRate = negTotal > 0 ? (double) negHits / negTotal : 0;

            System.out.printf("  %-8.2f  %-12.4f  %-12.4f  %s%n",
                    threshold, legalRate, falseRate,
                    (legalRate >= 0.95 && falseRate <= 0.02) ? "← 推荐" : "");
        }
    }
}
