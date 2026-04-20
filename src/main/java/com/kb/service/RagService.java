package com.kb.service;

import com.kb.model.CachedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * RAG 检索 Service（支持向量缓存 + Hybrid Search）
 * <p>
 * 负责：问题 Embedding → 向量缓存查询 → Hybrid 混合检索 → 上下文文本构建 → 缓存写入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final VectorCacheService vectorCacheService;
    private final HybridSearchService hybridSearchService;

    @Value("${kb.rag.top-k:5}")
    private int topK;

    @Value("${kb.rag.similarity-threshold:0.65}")
    private double similarityThreshold;

    @Value("${kb.rag.use-hybrid-search:true}")
    private boolean useHybridSearch;

    /**
     * 根据用户问题检索相关上下文片段
     * <p>
     * 支持两种检索模式：
     * - Hybrid Search（默认）：向量检索 + BM25 关键词检索融合
     * - Vector Search：纯向量检索
     *
     * @param question 用户原始问题
     * @return 相关文档片段列表
     */
    public List<Document> retrieveRelevantChunks(String question) {
        log.debug("[RagService] 开始检索，问题: {}", question);

        List<Document> results;
        if (useHybridSearch) {
            // Hybrid Search 混合检索
            results = hybridSearchService.hybridSearch(question, topK);
        } else {
            // 纯向量检索
            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .build();
            results = vectorStore.similaritySearch(request);
        }

        log.info("[RagService] 检索完成，命中 {} 个相关片段（topK={}, mode={}）",
                results.size(), topK, useHybridSearch ? "hybrid" : "vector");

        return results;
    }

    /**
     * 将检索到的文档片段拼接为上下文字符串，供 SystemPrompt 填充。
     *
     * @param chunks 检索到的文档片段
     * @return 格式化的上下文文本
     */
    public String buildContext(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return "（未找到相关知识库内容）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String filename = (String) chunk.getMetadata().getOrDefault("filename", "未知文档");
            Integer chunkIndex = (Integer) chunk.getMetadata().getOrDefault("chunk_index", 0);

            sb.append("--- 来源: ").append(filename)
              .append(" [片段 ").append(chunkIndex + 1).append("] ---\n")
              .append(chunk.getText().trim())
              .append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 一步完成：检索 + 构建上下文（支持向量缓存）
     * <p>
     * 优化流程：
     * 1. 先查询向量缓存（基于语义相似度）
     * 2. 命中缓存则直接返回
     * 3. 未命中则执行 VectorStore 检索
     * 4. 将检索结果写入缓存
     *
     * @param question 用户问题
     * @return 格式化上下文文本
     */
    public String retrieveAndBuildContext(String question) {
        // 1. 查询向量缓存
        Optional<CachedResult> cachedResult = vectorCacheService.findSimilar(question);
        if (cachedResult.isPresent()) {
            log.info("[RagService] 向量缓存命中，相似度: {:.4f}", cachedResult.get().getSimilarity());
            return cachedResult.get().getContext();
        }

        // 2. 缓存未命中，执行全量检索
        log.info("[RagService] 向量缓存未命中，执行 VectorStore 检索");
        List<Document> chunks = retrieveRelevantChunks(question);
        String context = buildContext(chunks);

        // 3. 提取来源文档
        List<String> sources = chunks.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("filename", "未知"))
                .distinct()
                .collect(Collectors.toList());

        // 4. 写入缓存
        vectorCacheService.put(question, context, sources);

        return context;
    }

    /**
     * 返回命中的来源文档名称列表（支持向量缓存）
     */
    public List<String> retrieveSourceDocuments(String question) {
        // 1. 查询向量缓存
        Optional<CachedResult> cachedResult = vectorCacheService.findSimilar(question);
        if (cachedResult.isPresent()) {
            return cachedResult.get().getSources();
        }

        // 2. 缓存未命中，执行检索
        return retrieveRelevantChunks(question).stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("filename", "未知"))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 清空向量缓存（文档更新时调用）
     */
    public void invalidateCache() {
        vectorCacheService.invalidateAll();
        log.info("[RagService] 向量缓存已清空");
    }
}
