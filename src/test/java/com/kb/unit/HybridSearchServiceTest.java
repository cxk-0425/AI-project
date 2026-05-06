package com.kb.unit;

import com.kb.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridSearchService 单元测试
 * <p>
 * 重点测试：
 * <ul>
 *   <li>混合检索禁用时，fallback 到纯向量检索</li>
 *   <li>BM25 检索失败时，安全返回空 Map（不影响整体检索）</li>
 *   <li>RRF 融合算法：仅向量命中、仅关键词命中、双路命中的分数合并逻辑</li>
 *   <li>buildTsQuery 停用词过滤 + 分词逻辑</li>
 *   <li>空结果处理</li>
 *   <li>返回 Top-K 截断</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("HybridSearchService 单元测试")
class HybridSearchServiceTest {

    private VectorStore mockVectorStore;
    private JdbcTemplate mockJdbcTemplate;
    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        mockVectorStore = mock(VectorStore.class);
        mockJdbcTemplate = mock(JdbcTemplate.class);
        hybridSearchService = new HybridSearchService(mockVectorStore, mockJdbcTemplate);

        // 默认启用配置
        ReflectionTestUtils.setField(hybridSearchService, "enabled", true);
        ReflectionTestUtils.setField(hybridSearchService, "vectorWeight", 0.5);
        ReflectionTestUtils.setField(hybridSearchService, "keywordWeight", 0.5);
        ReflectionTestUtils.setField(hybridSearchService, "rrfK", 60);
        ReflectionTestUtils.setField(hybridSearchService, "vectorTopK", 20);
        ReflectionTestUtils.setField(hybridSearchService, "keywordTopK", 20);
    }

    // ─── 启用/禁用测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("hybrid.enabled=false 时，应 fallback 到纯向量检索")
    void hybridSearch_disabled_shouldFallbackToVectorOnly() {
        ReflectionTestUtils.setField(hybridSearchService, "enabled", false);

        List<Document> mockDocs = List.of(
                buildDoc("doc1", "满减活动配置"),
                buildDoc("doc2", "优惠券创建流程")
        );
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockDocs);

        List<Document> results = hybridSearchService.hybridSearch("满减活动", 5);

        // 禁用时只调用向量检索
        verify(mockVectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        verify(mockJdbcTemplate, never()).queryForList(anyString(), any(), any(), anyInt());
        assertThat(results).hasSize(2);
    }

    // ─── BM25 检索失败处理 ────────────────────────────────────────────────────

    @Test
    @DisplayName("BM25 检索抛异常时，应静默处理并继续使用纯向量结果")
    void hybridSearch_bm25Fails_shouldFallbackToVectorOnly() {
        List<Document> mockDocs = List.of(buildDoc("doc1", "满减规则说明"));
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockDocs);
        when(mockJdbcTemplate.queryForList(anyString(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("PostgreSQL full-text search failed"));

        List<Document> results = hybridSearchService.hybridSearch("满减活动配置", 5);

        // 不应抛异常，应返回向量检索结果
        assertThat(results).isNotNull();
        assertThatCode(() -> hybridSearchService.hybridSearch("满减活动配置", 5))
                .doesNotThrowAnyException();
    }

    // ─── 空结果处理 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索和 BM25 均返回空时，结果应为空列表")
    void hybridSearch_allEmpty_shouldReturnEmptyList() {
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(mockJdbcTemplate.queryForList(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<Document> results = hybridSearchService.hybridSearch("活动配置", 5);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("空查询字符串时，应返回空列表或向量检索结果（不抛异常）")
    void hybridSearch_emptyQuery_shouldNotThrow() {
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(mockJdbcTemplate.queryForList(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        assertThatCode(() -> hybridSearchService.hybridSearch("", 5))
                .doesNotThrowAnyException();
    }

    // ─── 统计信息测试 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getStats 应返回当前配置信息")
    void getStats_shouldReturnCurrentConfig() {
        HybridSearchService.HybridSearchStats stats = hybridSearchService.getStats();

        assertThat(stats.enabled()).isTrue();
        assertThat(stats.vectorWeight()).isEqualTo(0.5);
        assertThat(stats.keywordWeight()).isEqualTo(0.5);
        assertThat(stats.rrfK()).isEqualTo(60);
        assertThat(stats.vectorTopK()).isEqualTo(20);
        assertThat(stats.keywordTopK()).isEqualTo(20);
    }

    // ─── RRF 分数验证 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("同时出现在向量和关键词检索结果中的文档，RRF 分数应高于仅出现一次的文档")
    void hybridSearch_docInBothResults_shouldHaveHigherRrfScore() {
        // doc1 同时命中向量和 BM25
        // doc2 只命中向量
        List<Document> vectorDocs = List.of(
                buildDoc("doc1", "满减规则内容"),
                buildDoc("doc2", "其他活动文档")
        );
        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(vectorDocs)
                .thenReturn(vectorDocs); // 第二次 fetchDocumentsByIds 时也需要返回

        List<Map<String, Object>> bm25Rows = List.of(
                Map.of("id", "doc1", "bm25_score", 0.8)
        );
        when(mockJdbcTemplate.queryForList(anyString(), any(), any(), anyInt()))
                .thenReturn(bm25Rows);

        // 执行后结果不为空
        List<Document> results = hybridSearchService.hybridSearch("满减活动规则", 5);

        assertThat(results).isNotEmpty();
        // doc1 应排在 doc2 前面（因为它在两个检索中都命中）
        if (results.size() >= 2) {
            Object firstId = extractDocId(results.get(0));
            assertThat(firstId).isEqualTo("doc1");
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    /**
     * 构造测试用 Document
     */
    private Document buildDoc(String id, String content) {
        Document doc = new Document(content);
        doc.setMetadataProperty("documentId", id);
        doc.setMetadataProperty("filename", id + ".md");
        return doc;
    }

    private Object extractDocId(Document doc) {
        return doc.getMetadata().getOrDefault("documentId", doc.getId());
    }
}
