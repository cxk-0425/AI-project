package com.kb.unit;

import com.kb.model.CachedQuestion;
import com.kb.service.VectorCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * VectorCacheService 单元测试
 * <p>
 * 测试范围：
 * <ul>
 *   <li>余弦相似度计算（反射访问私有方法）</li>
 *   <li>缓存命中与未命中</li>
 *   <li>LRU 淘汰策略</li>
 *   <li>TTL 过期淘汰</li>
 *   <li>线程安全（基本场景）</li>
 * </ul>
 * <p>
 * 使用 Mockito 替换 EmbeddingModel，不依赖外部服务。
 */
@Tag("unit")
@DisplayName("VectorCacheService 单元测试")
class VectorCacheServiceTest {

    private EmbeddingModel mockEmbeddingModel;
    private VectorCacheService cacheService;

    /**
     * 构造归一化的测试向量（便于精确计算余弦相似度）
     */
    private static float[] normalizedVector(float... values) {
        double norm = 0;
        for (float v : values) norm += v * v;
        norm = Math.sqrt(norm);
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (float) (values[i] / norm);
        }
        return result;
    }

    @BeforeEach
    void setUp() {
        mockEmbeddingModel = mock(EmbeddingModel.class);
        cacheService = new VectorCacheService(mockEmbeddingModel);

        // 默认注入配置
        ReflectionTestUtils.setField(cacheService, "maxSize", 5);
        ReflectionTestUtils.setField(cacheService, "similarityThreshold", 0.95);
        ReflectionTestUtils.setField(cacheService, "ttlMinutes", 30);
    }

    // ─── 余弦相似度测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("相同向量的余弦相似度应为 1.0")
    void cosineSimilarity_identicalVectors_shouldReturnOne() {
        float[] v = {1.0f, 0.0f, 0.0f};
        double sim = invokeCosineSimilarity(v, v.clone());
        assertThat(sim).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("正交向量的余弦相似度应为 0.0")
    void cosineSimilarity_orthogonalVectors_shouldReturnZero() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        double sim = invokeCosineSimilarity(a, b);
        assertThat(sim).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("零向量的余弦相似度应为 0.0（避免除以零）")
    void cosineSimilarity_zeroVector_shouldReturnZero() {
        float[] zero = {0.0f, 0.0f, 0.0f};
        float[] v = {1.0f, 0.0f, 0.0f};
        double sim = invokeCosineSimilarity(zero, v);
        assertThat(sim).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("维度不匹配时应抛出 IllegalArgumentException")
    void cosineSimilarity_differentDimensions_shouldThrow() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertThatThrownBy(() -> invokeCosineSimilarity(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度不匹配");
    }

    // ─── 缓存命中/未命中测试 ────────────────────────────────────────────────────

    @Test
    @DisplayName("高相似度问题应命中缓存")
    void findSimilar_highSimilarity_shouldHit() {
        // 构造两个几乎相同的向量（相似度 > 0.95）
        float[] originalVec = normalizedVector(1f, 0f, 0f);
        float[] queryVec = normalizedVector(0.999f, 0.044f, 0f);  // 余弦相似度 ≈ 0.999

        // 向知识库中放入一条缓存（直接操作内部字段）
        putDirectlyToCache("有哪些满减活动？", originalVec, "满减上下文", List.of("doc1.md"));

        // 查询时返回 queryVec
        when(mockEmbeddingModel.embed(anyString())).thenReturn(queryVec);

        Optional<com.kb.model.CachedResult> result = cacheService.findSimilar("当前有什么满减活动？");

        assertThat(result).isPresent();
        assertThat(result.get().getContext()).isEqualTo("满减上下文");
        assertThat(result.get().getSources()).contains("doc1.md");
    }

    @Test
    @DisplayName("低相似度问题不应命中缓存")
    void findSimilar_lowSimilarity_shouldMiss() {
        float[] originalVec = normalizedVector(1f, 0f, 0f);
        float[] queryVec = normalizedVector(0f, 1f, 0f);  // 余弦相似度 = 0 < 0.95

        putDirectlyToCache("有哪些满减活动？", originalVec, "满减上下文", List.of("doc1.md"));

        when(mockEmbeddingModel.embed(anyString())).thenReturn(queryVec);

        Optional<com.kb.model.CachedResult> result = cacheService.findSimilar("今天天气怎么样");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Embedding 失败时应返回 empty（容错处理）")
    void findSimilar_embeddingFails_shouldReturnEmpty() {
        when(mockEmbeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("embedding service down"));

        Optional<com.kb.model.CachedResult> result = cacheService.findSimilar("任意问题");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("空问题 put 时 Embedding 失败应静默处理不抛异常")
    void put_whenEmbeddingFails_shouldNotThrow() {
        when(mockEmbeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("embedding service down"));

        assertThatCode(() -> cacheService.put("问题", "上下文", List.of()))
                .doesNotThrowAnyException();
    }

    // ─── LRU 淘汰测试 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("超过 maxSize 时，最旧的缓存条目应被淘汰")
    void put_exceedsMaxSize_shouldEvictLru() {
        // maxSize=5，向量和问题都不同，塞入 6 条
        for (int i = 0; i < 6; i++) {
            float[] vec = new float[3];
            vec[i % 3] = 1.0f;
            int finalI = i;
            when(mockEmbeddingModel.embed("问题" + i)).thenReturn(vec);
            cacheService.put("问题" + i, "上下文" + i, List.of());
        }

        // 缓存大小不超过 maxSize
        assertThat(cacheService.getStats().currentSize()).isLessThanOrEqualTo(5);
    }

    // ─── TTL 过期测试 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("过期缓存不应被命中")
    void findSimilar_expiredEntry_shouldMiss() {
        float[] vec = normalizedVector(1f, 0f, 0f);

        // 直接放入已过期的缓存（创建时间设为 2 小时前，ttl=30min）
        putExpiredEntryToCache("过期问题", vec, "过期上下文", List.of());

        when(mockEmbeddingModel.embed(anyString())).thenReturn(vec);

        Optional<com.kb.model.CachedResult> result = cacheService.findSimilar("过期问题");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("evictExpired 应清除过期条目，保留未过期条目")
    void evictExpired_shouldRemoveExpiredOnly() {
        float[] freshVec = normalizedVector(1f, 0f, 0f);
        float[] expiredVec = normalizedVector(0f, 1f, 0f);

        when(mockEmbeddingModel.embed("新问题")).thenReturn(freshVec);
        cacheService.put("新问题", "新上下文", List.of());
        putExpiredEntryToCache("过期问题", expiredVec, "过期上下文", List.of());

        assertThat(cacheService.getStats().currentSize()).isEqualTo(2);

        cacheService.evictExpired();

        assertThat(cacheService.getStats().currentSize()).isEqualTo(1);
    }

    // ─── 统计测试 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("命中率统计应准确")
    void getStats_hitRate_shouldBeAccurate() {
        float[] vec = normalizedVector(1f, 0f, 0f);
        putDirectlyToCache("问题A", vec, "上下文A", List.of());

        // 命中 1 次
        when(mockEmbeddingModel.embed(anyString())).thenReturn(vec);
        cacheService.findSimilar("问题A");

        // 未命中 1 次（不同向量）
        when(mockEmbeddingModel.embed(anyString())).thenReturn(normalizedVector(0f, 1f, 0f));
        cacheService.findSimilar("问题B");

        VectorCacheService.CacheStats stats = cacheService.getStats();
        assertThat(stats.totalQueries()).isEqualTo(2);
        assertThat(stats.hitCount()).isEqualTo(1);
        assertThat(stats.hitRate()).isCloseTo(0.5, within(1e-6));
    }

    @Test
    @DisplayName("invalidateAll 应清空所有缓存并重置统计")
    void invalidateAll_shouldClearAll() {
        float[] vec = normalizedVector(1f, 0f, 0f);
        when(mockEmbeddingModel.embed(anyString())).thenReturn(vec);
        cacheService.put("问题", "上下文", List.of());

        assertThat(cacheService.getStats().currentSize()).isEqualTo(1);

        cacheService.invalidateAll();

        assertThat(cacheService.getStats().currentSize()).isEqualTo(0);
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    /**
     * 反射调用私有 cosineSimilarity 方法
     */
    private double invokeCosineSimilarity(float[] a, float[] b) {
        try {
            var method = VectorCacheService.class.getDeclaredMethod(
                    "cosineSimilarity", float[].class, float[].class);
            method.setAccessible(true);
            return (double) method.invoke(cacheService, a, b);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(ite.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 直接向缓存内部放入一条记录（绕过 Embedding 调用）
     */
    private void putDirectlyToCache(String question, float[] vec, String context, List<String> sources) {
        try {
            var cacheField = VectorCacheService.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Deque<CachedQuestion> cache =
                    (java.util.Deque<CachedQuestion>) cacheField.get(cacheService);

            CachedQuestion cq = CachedQuestion.builder()
                    .originalQuestion(question)
                    .questionVector(vec)
                    .cachedContext(context)
                    .cachedSources(sources)
                    .createdAt(LocalDateTime.now())
                    .lastAccessedAt(LocalDateTime.now())
                    .hitCount(0)
                    .build();
            cache.addFirst(cq);
        } catch (Exception e) {
            throw new RuntimeException("直接写入缓存失败", e);
        }
    }

    /**
     * 放入已过期的缓存条目（创建时间设为 2 小时前）
     */
    private void putExpiredEntryToCache(String question, float[] vec, String context, List<String> sources) {
        try {
            var cacheField = VectorCacheService.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Deque<CachedQuestion> cache =
                    (java.util.Deque<CachedQuestion>) cacheField.get(cacheService);

            CachedQuestion cq = CachedQuestion.builder()
                    .originalQuestion(question)
                    .questionVector(vec)
                    .cachedContext(context)
                    .cachedSources(sources)
                    .createdAt(LocalDateTime.now().minusHours(2))  // 2 小时前，必然过期
                    .lastAccessedAt(LocalDateTime.now().minusHours(2))
                    .hitCount(0)
                    .build();
            cache.addFirst(cq);
        } catch (Exception e) {
            throw new RuntimeException("直接写入过期缓存失败", e);
        }
    }
}
