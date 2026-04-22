package com.kb.service;

import com.kb.model.CachedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 电商营销内部文档 RAG 服务（查询类路径）
 * <p>
 * 专门检索电商营销内部文档（产品说明、营销策略、内部规范等），
 * 用于回答查询类（QUERY）的用户问题。
 * <p>
 * 注入 @Primary 的 docVectorStore。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingDocRagService {

    @Qualifier("docVectorStore")
    private final VectorStore docVectorStore;

    private final VectorCacheService vectorCacheService;
    private final HybridSearchService hybridSearchService;

    @Value("${kb.rag.top-k:5}")
    private int topK;

    @Value("${kb.rag.similarity-threshold:0.65}")
    private double similarityThreshold;

    @Value("${kb.rag.use-hybrid-search:true}")
    private boolean useHybridSearch;

    /**
     * 检索电商营销内部文档的相关片段
     */
    public List<Document> retrieveRelevantChunks(String question) {
        log.debug("[MarketingDocRagService] 开始检索内部文档，问题: {}", question);

        List<Document> results;
        if (useHybridSearch) {
            results = hybridSearchService.hybridSearch(question, topK);
        } else {
            results = docVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(topK)
                            .similarityThreshold(similarityThreshold)
                            .build()
            );
        }

        log.info("[MarketingDocRagService] 检索完成，命中 {} 个片段", results.size());
        return results;
    }

    /**
     * 将文档片段构建为上下文字符串
     */
    public String buildContext(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return "（未在营销内部文档库中找到相关内容）";
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
     */
    public String retrieveAndBuildContext(String question) {
        // 1. 查询向量缓存
        Optional<CachedResult> cachedResult = vectorCacheService.findSimilar(question);
        if (cachedResult.isPresent()) {
            log.info("[MarketingDocRagService] 向量缓存命中，相似度: {}", cachedResult.get().getSimilarity());
            return cachedResult.get().getContext();
        }

        // 2. 缓存未命中，执行检索
        List<Document> chunks = retrieveRelevantChunks(question);
        String context = buildContext(chunks);

        // 3. 写入缓存
        List<String> sources = chunks.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("filename", "未知"))
                .distinct()
                .collect(Collectors.toList());
        vectorCacheService.put(question, context, sources);

        return context;
    }

    /**
     * 获取来源文档列表
     */
    public List<String> retrieveSourceDocuments(String question) {
        Optional<CachedResult> cachedResult = vectorCacheService.findSimilar(question);
        if (cachedResult.isPresent()) {
            return cachedResult.get().getSources();
        }
        return retrieveRelevantChunks(question).stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("filename", "未知"))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 清空缓存
     */
    public void invalidateCache() {
        vectorCacheService.invalidateAll();
        log.info("[MarketingDocRagService] 向量缓存已清空");
    }
}
