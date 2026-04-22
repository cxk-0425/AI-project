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
 * 营销 QA + 活动 SOP 文档 RAG 服务（配置类路径）
 * <p>
 * 专门检索营销 QA 问答库和活动 SOP 文档，用于为配置类（CONFIG）操作提供
 * 流程指引和步骤说明，下游由 PlannerAgent 进行拆步处理。
 * <p>
 * 注入 @Qualifier("sopVectorStore")，使用独立的 sop_vector_store 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SopRagService {

    @Qualifier("sopVectorStore")
    private final VectorStore sopVectorStore;

    @Value("${kb.rag.top-k:5}")
    private int topK;

    @Value("${kb.rag.similarity-threshold:0.60}")
    private double similarityThreshold;

    /**
     * 检索 SOP 相关文档片段
     * <p>
     * SOP 文档通常以自然语言描述操作流程，相似度阈值可适当降低（0.60），
     * 以提升召回覆盖率，由 PlannerAgent 做进一步的步骤筛选。
     *
     * @param userQuery 用户原始意图（配置类问题）
     * @return SOP 相关文档片段列表
     */
    public List<Document> retrieveSopChunks(String userQuery) {
        log.debug("[SopRagService] 开始检索 SOP 文档，查询: {}", userQuery);

        List<Document> results = sopVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userQuery)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );

        log.info("[SopRagService] SOP 检索完成，命中 {} 个片段", results.size());
        return results;
    }

    /**
     * 将 SOP 片段整理为操作流程上下文
     * <p>
     * SOP 上下文格式更侧重流程编号和步骤描述，以便 PlannerAgent 能清晰提取步骤。
     *
     * @param chunks SOP 文档片段
     * @return 结构化流程上下文文本
     */
    public String buildSopContext(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return "（未在 SOP 文档库中找到相关操作流程，请参考通用营销活动创建步骤）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【营销活动操作 SOP 参考流程】\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String filename = (String) chunk.getMetadata().getOrDefault("filename", "SOP 文档");
            Integer chunkIndex = (Integer) chunk.getMetadata().getOrDefault("chunk_index", i);

            sb.append("--- 来源: ").append(filename)
              .append(" [片段 ").append(chunkIndex + 1).append("] ---\n")
              .append(chunk.getText().trim())
              .append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 一步完成：检索 SOP + 构建流程上下文（供 PlannerAgent 使用）
     */
    public String retrieveAndBuildSopContext(String userQuery) {
        List<Document> chunks = retrieveSopChunks(userQuery);
        return buildSopContext(chunks);
    }

    /**
     * 获取命中的 SOP 文档来源列表
     */
    public List<String> retrieveSopSources(String userQuery) {
        return retrieveSopChunks(userQuery).stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("filename", "未知 SOP"))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 向 SOP 库中添加文档（供文档上传接口调用）
     */
    public void addDocuments(List<Document> documents) {
        sopVectorStore.add(documents);
        log.info("[SopRagService] 已添加 {} 个文档片段到 SOP 向量库", documents.size());
    }
}
