package com.kb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid Search Service - 混合检索服务
 * <p>
 * 结合向量相似度检索和 BM25 关键词检索，通过 RRF 算法融合两种检索结果。
 * <p>
 * 核心优势：
 * <ul>
 *   <li>向量检索：理解语义，匹配同义词（如"NFC" ≈ "近场通信"）</li>
 *   <li>关键词检索：精确匹配关键词（如搜索"Apple iPhone"时优先返回含该词的文档）</li>
 *   <li>RRF 融合：综合两种检索的优势，提升整体检索质量</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Value("${kb.hybrid.vector-weight:0.5}")
    private double vectorWeight;

    @Value("${kb.hybrid.keyword-weight:0.5}")
    private double keywordWeight;

    @Value("${kb.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${kb.hybrid.vector-top-k:20}")
    private int vectorTopK;

    @Value("${kb.hybrid.keyword-top-k:20}")
    private int keywordTopK;

    @Value("${kb.hybrid.enabled:true}")
    private boolean enabled;

    /**
     * 执行混合检索
     * <p>
     * 流程：
     * 1. 并行执行向量检索 + BM25 检索
     * 2. 使用 RRF 算法融合结果
     * 3. 返回融合排序后的文档列表
     *
     * @param question 用户问题
     * @param topK     返回结果数量
     * @return 混合检索结果列表
     */
    public List<Document> hybridSearch(String question, int topK) {
        if (!enabled) {
            // 混合检索未启用，回退到纯向量检索
            return fallbackToVectorSearch(question, topK);
        }

        long startTime = System.currentTimeMillis();

        // 1. 并行执行两种检索
        Map<String, Double> vectorScores = vectorSearch(question);
        Map<String, Double> keywordScores = keywordSearch(question);

        // 2. RRF 融合
        Map<String, Double> fusedScores = reciprocalRankFusion(vectorScores, keywordScores);

        // 3. 按融合分数排序并返回 Top-K
        List<Map.Entry<String, Double>> sorted = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // 4. 获取完整的 Document 对象
        List<Document> results = fetchDocumentsByIds(sorted);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[HybridSearch] 混合检索完成，问题: {}，命中 {} 个结果，耗时: {}ms",
                question, results.size(), elapsed);

        // 打印检索分析
        printSearchAnalysis(question, vectorScores, keywordScores, fusedScores);

        return results;
    }

    /**
     * 向量相似度检索
     * <p>
     * 返回 Map：documentId -> 相似度分数
     */
    private Map<String, Double> vectorSearch(String question) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(vectorTopK)
                .similarityThreshold(0.0)  // 获取所有结果，后续通过 RRF 融合
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);

        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String id = extractDocumentId(doc);
            // 相似度分数：排名第 i 名，分数 = 1 - (i / vectorTopK)
            double score = 1.0 - ((double) i / docs.size());
            scores.put(id, score);
            doc.setMetadataProperty("_hybrid_score_vector", score);
        }

        log.debug("[HybridSearch] 向量检索命中 {} 个结果", docs.size());
        return scores;
    }

    /**
     * BM25 关键词检索
     * <p>
     * 使用 PostgreSQL 全文检索实现 BM25 算法。
     * 返回 Map：documentId -> BM25 分数
     */
    private Map<String, Double> keywordSearch(String question) {
        try {
            // 1. 将用户问题转换为 tsquery 格式
            String tsQuery = buildTsQuery(question);

            // 2. 执行全文检索
            String sql = """
                SELECT id, 
                       COALESCE(ts_rank_cd(to_tsvector('chinese', text_content), ?, 32), 0) AS bm25_score
                FROM vector_store
                WHERE to_tsvector('chinese', text_content) @@ ?
                ORDER BY bm25_score DESC
                LIMIT ?
                """;

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tsQuery, tsQuery, keywordTopK);

            Map<String, Double> scores = new LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                String id = row.get("id").toString();
                double bm25Score = ((Number) row.get("bm25_score")).doubleValue();
                scores.put(id, bm25Score);
            }

            log.debug("[HybridSearch] 关键词检索命中 {} 个结果", scores.size());
            return scores;

        } catch (Exception e) {
            log.warn("[HybridSearch] 关键词检索失败，回退到向量检索: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 构建 PostgreSQL 全文检索查询
     * <p>
     * 将用户问题转换为 tsquery 格式：
     * - 提取关键词
     * - 转换为 tsquery（支持 AND/OR 操作）
     */
    private String buildTsQuery(String question) {
        // 简单分词：按空格和标点分割
        String[] words = question.split("[\\s，。！？、,.!?]+");
        
        // 过滤停用词和太短的词
        words = Arrays.stream(words)
                .filter(w -> w.length() >= 2)
                .filter(w -> !isStopWord(w))
                .toArray(String[]::new);

        if (words.length == 0) {
            return "'" + question + "'";
        }

        // 使用 & 操作符连接（AND 关系）
        return Arrays.stream(words)
                .map(w -> w.toLowerCase())
                .collect(Collectors.joining(" & "));
    }

    /**
     * 简单停用词列表
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
                "的", "了", "是", "在", "和", "与", "或", "及", "等", "把", "被",
                "我", "你", "他", "她", "它", "我们", "你们", "他们",
                "这个", "那个", "什么", "怎么", "如何", "为什么"
        );
        return stopWords.contains(word.toLowerCase());
    }

    /**
     * RRF (Reciprocal Rank Fusion) 算法
     * <p>
     * 将多个排序列表融合为一个综合排序。
     * RRF 分数 = Σ (1 / (k + rank))
     * <p>
     * 参数 k：通常设为 60，k 值越大，不同排序间的差异权重越小
     */
    private Map<String, Double> reciprocalRankFusion(
            Map<String, Double> vectorScores,
            Map<String, Double> keywordScores) {

        Map<String, Double> fusedScores = new LinkedHashMap<>();

        // 初始化分数
        for (String id : vectorScores.keySet()) {
            fusedScores.put(id, 0.0);
        }
        for (String id : keywordScores.keySet()) {
            fusedScores.putIfAbsent(id, 0.0);
        }

        // 1. 融合向量检索分数
        List<String> vectorRanked = vectorScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (int rank = 0; rank < vectorRanked.size(); rank++) {
            String id = vectorRanked.get(rank);
            double vectorWeightFactor = vectorScores.get(id);
            double rrfScore = 1.0 / (rrfK + rank + 1);
            fusedScores.merge(id, vectorWeightFactor * rrfScore, Double::sum);
        }

        // 2. 融合 BM25 检索分数
        List<String> keywordRanked = keywordScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 归一化 BM25 分数
        double maxBm25 = keywordRanked.stream()
                .mapToDouble(keywordScores::get)
                .max()
                .orElse(1.0);

        for (int rank = 0; rank < keywordRanked.size(); rank++) {
            String id = keywordRanked.get(rank);
            double normalizedBm25 = maxBm25 > 0 ? keywordScores.get(id) / maxBm25 : 0;
            double rrfScore = 1.0 / (rrfK + rank + 1);
            fusedScores.merge(id, keywordWeight * normalizedBm25 * rrfScore, Double::sum);
        }

        return fusedScores;
    }

    /**
     * 根据 documentId 获取完整的 Document 对象
     */
    private List<Document> fetchDocumentsByIds(List<Map.Entry<String, Double>> sortedIds) {
        if (sortedIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建 ID 列表和分数映射
        List<String> ids = sortedIds.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        Map<String, Double> scoreMap = sortedIds.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // 使用向量检索获取完整 Document（保持原有结构）
        String queryText = "";  // 空查询，获取所有文档后过滤
        SearchRequest request = SearchRequest.builder()
                .query(queryText)
                .topK(1000)
                .similarityThreshold(0.0)
                .build();

        List<Document> allDocs = vectorStore.similaritySearch(request);

        // 过滤出需要的文档并按分数排序
        Map<String, Document> docMap = allDocs.stream()
                .collect(Collectors.toMap(this::extractDocumentId, d -> d, (d1, d2) -> d1));

        List<Document> results = new ArrayList<>();
        for (String id : ids) {
            Document doc = docMap.get(id);
            if (doc != null) {
                // 添加混合检索分数到 metadata
                doc.setMetadataProperty("_hybrid_score", scoreMap.get(id));
                results.add(doc);
            }
        }

        return results;
    }

    /**
     * 从 Document 中提取 ID
     */
    private String extractDocumentId(Document doc) {
        Object id = doc.getMetadata().get("documentId");
        return id != null ? id.toString() : doc.getId();
    }

    /**
     * 回退到纯向量检索
     */
    private List<Document> fallbackToVectorSearch(String question, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(0.65)
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * 打印检索分析日志
     */
    private void printSearchAnalysis(String question, 
                                     Map<String, Double> vectorScores,
                                     Map<String, Double> keywordScores,
                                     Map<String, Double> fusedScores) {
        
        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug("[HybridSearch] ===== 检索分析 =====");
        log.debug("[HybridSearch] 问题: {}", question);
        
        // 向量检索 Top-3
        log.debug("[HybridSearch] 向量检索 Top-3:");
        vectorScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> log.debug("  - {}: {:.4f}", e.getKey(), e.getValue()));

        // 关键词检索 Top-3
        if (!keywordScores.isEmpty()) {
            log.debug("[HybridSearch] 关键词检索 Top-3:");
            keywordScores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> log.debug("  - {}: {:.4f}", e.getKey(), e.getValue()));
        } else {
            log.debug("[HybridSearch] 关键词检索: 无命中结果");
        }

        // 融合结果 Top-3
        log.debug("[HybridSearch] 融合结果 Top-3:");
        fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> log.debug("  - {}: {:.4f}", e.getKey(), e.getValue()));
    }

    /**
     * 获取检索统计信息
     */
    public HybridSearchStats getStats() {
        return new HybridSearchStats(
                enabled,
                vectorWeight,
                keywordWeight,
                rrfK,
                vectorTopK,
                keywordTopK
        );
    }

    /**
     * 混合检索配置信息
     */
    public record HybridSearchStats(
            boolean enabled,
            double vectorWeight,
            double keywordWeight,
            int rrfK,
            int vectorTopK,
            int keywordTopK
    ) {}
}
