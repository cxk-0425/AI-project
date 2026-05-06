package com.kb.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kb.eval.util.EvalDatasetLoader;
import com.kb.eval.util.EvalMetricsCalculator;
import com.kb.eval.util.EvalReportPrinter;
import com.kb.service.HybridSearchService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索质量评测测试
 * <p>
 * 对比混合检索（Hybrid Search）与纯向量检索在以下指标上的差异：
 * <ul>
 *   <li>Recall@5 / Precision@5 / NDCG@5 / MRR</li>
 * </ul>
 * <p>
 * 前置条件：
 * <ul>
 *   <li>数据库中已录入测试文档，并在 {@code eval/rag_dataset.json} 中填写真实文档 ID</li>
 *   <li>如果 rag_dataset.json 中 relevant_doc_ids 为空，测试将跳过断言，仅输出说明</li>
 * </ul>
 * <p>
 * 运行命令：
 * <pre>
 *   mvn test -Dgroups="model-eval" -Dtest="RagEvaluationTest"
 * </pre>
 */
@Tag("eval")
@Tag("model-eval")
@SpringBootTest
class RagEvaluationTest {

    @Autowired
    private HybridSearchService hybridSearchService;

    @Qualifier("docVectorStore")
    @Autowired
    private VectorStore docVectorStore;

    private static final int K = 5;

    // ─── 评测数据集结构 ────────────────────────────────────────────────────────

    /**
     * RAG 评测数据集单条记录
     */
    record RagTestCase(
            @JsonProperty("query") String query,
            @JsonProperty("relevant_doc_ids") List<String> relevantDocIds,
            @JsonProperty("relevance_grades") Map<String, Integer> relevanceGrades,
            @JsonProperty("note") String note
    ) {}

    // ─── 主评测 ──────────────────────────────────────────────────────────────

    @Test
    void evaluateHybridSearchQuality() throws IOException {
        List<RagTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/rag_dataset.json", RagTestCase.class);

        assertThat(dataset).as("RAG 评测数据集不能为空").isNotEmpty();

        // 检查数据集是否有效（文档 ID 非空）
        boolean hasValidData = dataset.stream()
                .anyMatch(tc -> tc.relevantDocIds() != null && !tc.relevantDocIds().isEmpty());

        if (!hasValidData) {
            System.out.println("""
                    
                    ╔══════════════════════════════════════════════════════════════╗
                    ║  [RAG 评测] 评测数据集尚未完成标注                          ║
                    ║  请先向知识库录入测试文档，然后用真实文档 ID 替换            ║
                    ║  src/test/resources/eval/rag_dataset.json 中的占位符        ║
                    ╚══════════════════════════════════════════════════════════════╝
                    """);
            return;
        }

        // ── 1. 混合检索评测 ───────────────────────────────────────────────────
        List<List<String>> hybridResults = new ArrayList<>();
        List<Set<String>> relevantIdSets = new ArrayList<>();
        List<Map<String, Integer>> relevanceGrades = new ArrayList<>();

        for (RagTestCase tc : dataset) {
            if (tc.relevantDocIds() == null || tc.relevantDocIds().isEmpty()) continue;

            List<Document> docs = hybridSearchService.hybridSearch(tc.query(), K);
            List<String> retrievedIds = docs.stream()
                    .map(d -> extractDocId(d))
                    .collect(Collectors.toList());

            hybridResults.add(retrievedIds);
            relevantIdSets.add(new HashSet<>(tc.relevantDocIds()));
            relevanceGrades.add(tc.relevanceGrades() != null ? tc.relevanceGrades() : Map.of());
        }

        // ── 2. 纯向量检索评测（对比组） ────────────────────────────────────────
        List<List<String>> vectorResults = new ArrayList<>();
        for (RagTestCase tc : dataset) {
            if (tc.relevantDocIds() == null || tc.relevantDocIds().isEmpty()) continue;

            List<Document> docs = docVectorStore.similaritySearch(
                    SearchRequest.builder().query(tc.query()).topK(K).similarityThreshold(0.0).build()
            );
            vectorResults.add(docs.stream().map(this::extractDocId).collect(Collectors.toList()));
        }

        // ── 3. 计算指标 ───────────────────────────────────────────────────────
        int queryCount = hybridResults.size();

        double hybridRecall = 0, hybridPrecision = 0, hybridNdcg = 0;
        double vectorRecall = 0, vectorPrecision = 0, vectorNdcg = 0;

        for (int i = 0; i < queryCount; i++) {
            hybridRecall += EvalMetricsCalculator.recallAtK(hybridResults.get(i), relevantIdSets.get(i), K);
            hybridPrecision += EvalMetricsCalculator.precisionAtK(hybridResults.get(i), relevantIdSets.get(i), K);
            hybridNdcg += EvalMetricsCalculator.ndcgAtK(hybridResults.get(i), relevanceGrades.get(i), K);

            vectorRecall += EvalMetricsCalculator.recallAtK(vectorResults.get(i), relevantIdSets.get(i), K);
            vectorPrecision += EvalMetricsCalculator.precisionAtK(vectorResults.get(i), relevantIdSets.get(i), K);
            vectorNdcg += EvalMetricsCalculator.ndcgAtK(vectorResults.get(i), relevanceGrades.get(i), K);
        }

        hybridRecall /= queryCount;
        hybridPrecision /= queryCount;
        hybridNdcg /= queryCount;
        vectorRecall /= queryCount;
        vectorPrecision /= queryCount;
        vectorNdcg /= queryCount;

        double hybridMrr = EvalMetricsCalculator.mrr(hybridResults, relevantIdSets);
        double vectorMrr = EvalMetricsCalculator.mrr(vectorResults, relevantIdSets);

        // ── 4. 打印报告 ───────────────────────────────────────────────────────
        EvalReportPrinter.printRagReport(
                hybridRecall, hybridPrecision, hybridNdcg, hybridMrr,
                K, queryCount, hybridNdcg, vectorNdcg);

        System.out.printf("%n【纯向量检索对比】Recall@%d=%.4f  Precision@%d=%.4f  NDCG@%d=%.4f  MRR=%.4f%n",
                K, vectorRecall, K, vectorPrecision, K, vectorNdcg, vectorMrr);

        // ── 5. 断言 ───────────────────────────────────────────────────────────
        assertThat(hybridRecall)
                .as("Recall@%d 应 >= 0.75，实际: %.4f", K, hybridRecall)
                .isGreaterThanOrEqualTo(0.75);

        assertThat(hybridPrecision)
                .as("Precision@%d 应 >= 0.70，实际: %.4f", K, hybridPrecision)
                .isGreaterThanOrEqualTo(0.70);

        assertThat(hybridNdcg)
                .as("NDCG@%d 应 >= 0.72，实际: %.4f", K, hybridNdcg)
                .isGreaterThanOrEqualTo(0.72);

        assertThat(hybridMrr)
                .as("MRR 应 >= 0.65，实际: %.4f", hybridMrr)
                .isGreaterThanOrEqualTo(0.65);

        // 混合提升率
        if (vectorNdcg > 0) {
            double improvement = (hybridNdcg - vectorNdcg) / vectorNdcg * 100;
            assertThat(improvement)
                    .as("混合检索 NDCG 相对纯向量提升应 >= 5%%，实际: %.1f%%", improvement)
                    .isGreaterThanOrEqualTo(5.0);
        }
    }

    /**
     * 检索延迟评测（不依赖 Ground Truth，可单独运行）
     */
    @Test
    void evaluateSearchLatency() throws IOException {
        List<RagTestCase> dataset = EvalDatasetLoader.loadList(
                "eval/rag_dataset.json", RagTestCase.class);

        List<Double> hybridLatencies = new ArrayList<>();
        List<Double> vectorLatencies = new ArrayList<>();

        for (RagTestCase tc : dataset) {
            // 混合检索延迟
            long start = System.currentTimeMillis();
            hybridSearchService.hybridSearch(tc.query(), K);
            hybridLatencies.add((double)(System.currentTimeMillis() - start));

            // 纯向量检索延迟
            start = System.currentTimeMillis();
            docVectorStore.similaritySearch(
                    SearchRequest.builder().query(tc.query()).topK(K).similarityThreshold(0.0).build()
            );
            vectorLatencies.add((double)(System.currentTimeMillis() - start));
        }

        double hybridAvg = EvalMetricsCalculator.mean(hybridLatencies);
        double vectorAvg = EvalMetricsCalculator.mean(vectorLatencies);

        System.out.printf("%n【检索延迟对比】%n");
        System.out.printf("  混合检索平均耗时: %.1f ms%n", hybridAvg);
        System.out.printf("  纯向量检索平均耗时: %.1f ms%n", vectorAvg);
        System.out.printf("  延迟增加: %.1f ms (%.1f%%)%n",
                hybridAvg - vectorAvg,
                vectorAvg > 0 ? (hybridAvg - vectorAvg) / vectorAvg * 100 : 0);
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    private String extractDocId(Document doc) {
        Object id = doc.getMetadata().get("documentId");
        return id != null ? id.toString() : doc.getId();
    }
}
