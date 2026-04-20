package com.kb.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 缓存问题实体
 * <p>
 * 存储问题向量、检索到的上下文和来源文档，用于基于向量相似度的智能缓存。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedQuestion {

    /**
     * 原始问题文本
     */
    private String originalQuestion;

    /**
     * 问题向量（1536维，text-embedding-3-small）
     */
    private float[] questionVector;

    /**
     * 缓存的上下文文本
     */
    private String cachedContext;

    /**
     * 缓存的来源文档名称列表
     */
    private List<String> cachedSources;

    /**
     * 缓存创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 缓存命中次数
     */
    private int hitCount;

    /**
     * 更新最后访问时间
     */
    public void updateAccessTime() {
        this.lastAccessedAt = LocalDateTime.now();
        this.hitCount++;
    }

    /**
     * 检查是否过期
     *
     * @param ttlMinutes 过期时间（分钟）
     * @return true 表示已过期
     */
    public boolean isExpired(int ttlMinutes) {
        return createdAt.plusMinutes(ttlMinutes).isBefore(LocalDateTime.now());
    }
}
