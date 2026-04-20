package com.kb.service;

import com.kb.model.CachedQuestion;
import com.kb.model.CachedResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 向量缓存服务
 * <p>
 * 基于问题向量的余弦相似度实现智能缓存，语义相同但表述不同的问题可共享缓存。
 * <p>
 * 核心功能：
 * <ul>
 *   <li>向量相似度检索：通过余弦相似度查找相似问题</li>
 *   <li>LRU + TTL 策略：时间过期和容量限制双重淘汰</li>
 *   <li>线程安全：读写锁保证并发安全</li>
 *   <li>统计监控：命中率、缓存大小等指标</li>
 * </ul>
 */
@Slf4j
@Service
public class VectorCacheService {

    private final EmbeddingModel embeddingModel;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 缓存存储（使用 Deque 实现 LRU）
     */
    private final Deque<CachedQuestion> cache = new ConcurrentLinkedDeque<>();

    /**
     * 统计指标
     */
    private final AtomicInteger hitCount = new AtomicInteger(0);
    private final AtomicInteger missCount = new AtomicInteger(0);
    private final AtomicInteger totalQueries = new AtomicInteger(0);

    @Value("${kb.cache.max-size:500}")
    private int maxSize;

    @Value("${kb.cache.similarity-threshold:0.95}")
    private double similarityThreshold;

    @Value("${kb.cache.ttl-minutes:30}")
    private int ttlMinutes;

    public VectorCacheService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 查找相似问题的缓存
     *
     * @param question 用户问题
     * @return 缓存结果（如果命中）
     */
    public Optional<CachedResult> findSimilar(String question) {
        totalQueries.incrementAndGet();

        // 1. 问题向量化
        float[] queryVector = embedQuestion(question);
        if (queryVector == null) {
            return Optional.empty();
        }

        // 2. 第一阶段：使用读锁遍历缓存，计算相似度（只读操作）
        CachedQuestion hitCache = null;
        double hitSimilarity = 0.0;

        lock.readLock().lock();
        try {
            for (CachedQuestion cached : cache) {
                // 检查是否过期
                if (cached.isExpired(ttlMinutes)) {
                    continue;
                }

                // 计算余弦相似度
                double similarity = cosineSimilarity(queryVector, cached.getQuestionVector());

                if (similarity >= similarityThreshold) {
                    hitCache = cached;
                    hitSimilarity = similarity;
                    break;  // 找到就退出循环，避免长时间持有读锁
                }
            }
        } finally {
            lock.readLock().unlock();  // 释放读锁
        }

        // 3. 如果命中缓存
        if (hitCache != null) {
            hitCount.incrementAndGet();
            hitCache.updateAccessTime();

            // 第二阶段：LRU 更新需要写锁，释放读锁后再获取
            lock.writeLock().lock();
            try {
                cache.remove(hitCache);
                cache.addFirst(hitCache);
            } finally {
                lock.writeLock().unlock();
            }

            log.info("[VectorCache] 缓存命中 - 问题: {}, 相似度: {:.4f}, 原始问题: {}",
                    question, hitSimilarity, hitCache.getOriginalQuestion());

            return Optional.of(new CachedResult(
                    hitCache.getCachedContext(),
                    hitCache.getCachedSources(),
                    hitSimilarity
            ));
        }

        // 4. 未命中
        missCount.incrementAndGet();
        log.debug("[VectorCache] 缓存未命中 - 问题: {}", question);
        return Optional.empty();
    }

    /**
     * 添加缓存
     *
     * @param question 原始问题
     * @param context  检索到的上下文
     * @param sources  来源文档列表
     */
    public void put(String question, String context, List<String> sources) {
        // 问题向量化
        float[] queryVector = embedQuestion(question);
        if (queryVector == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            // 构建缓存对象
            CachedQuestion cached = CachedQuestion.builder()
                    .originalQuestion(question)
                    .questionVector(queryVector)
                    .cachedContext(context)
                    .cachedSources(sources)
                    .createdAt(LocalDateTime.now())
                    .lastAccessedAt(LocalDateTime.now())
                    .hitCount(0)
                    .build();

            // 添加到队头
            cache.addFirst(cached);

            // 容量控制：超过 maxSize 时删除队尾
            while (cache.size() > maxSize) {
                CachedQuestion removed = cache.removeLast();
                log.debug("[VectorCache] 缓存淘汰（LRU） - 问题: {}", removed.getOriginalQuestion());
            }

            log.info("[VectorCache] 缓存写入 - 问题: {}, 当前缓存大小: {}", question, cache.size());

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 清空所有缓存
     */
    public void invalidateAll() {
        lock.writeLock().lock();
        try {
            int oldSize = cache.size();
            cache.clear();
            log.info("[VectorCache] 缓存已清空，删除 {} 条记录", oldSize);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 清理过期缓存
     */
    public void evictExpired() {
        lock.writeLock().lock();
        try {
            int removedCount = 0;
            cache.removeIf(cached -> {
                boolean expired = cached.isExpired(ttlMinutes);
                if (expired) {
                    removedCount++;
                }
                return expired;
            });

            if (removedCount > 0) {
                log.info("[VectorCache] 清理过期缓存 {} 条", removedCount);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            int total = totalQueries.get();
            int hits = hitCount.get();
            int misses = missCount.get();
            double hitRate = total > 0 ? (double) hits / total : 0.0;

            return new CacheStats(
                    cache.size(),
                    maxSize,
                    hits,
                    misses,
                    total,
                    hitRate,
                    similarityThreshold,
                    ttlMinutes
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────────────

    /**
     * 问题向量化
     */
    private float[] embedQuestion(String question) {
        try {
            return embeddingModel.embed(question);
        } catch (Exception e) {
            log.error("[VectorCache] 向量化失败 - 问题: {}", question, e);
            return null;
        }
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 移动元素到队头（LRU 更新）
     */
    private void moveToFront(CachedQuestion cached) {
        lock.writeLock().lock();
        try {
            cache.remove(cached);
            cache.addFirst(cached);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(
            int currentSize,
            int maxSize,
            int hitCount,
            int missCount,
            int totalQueries,
            double hitRate,
            double similarityThreshold,
            int ttlMinutes
    ) {
    }
}
