package com.kb.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 缓存命中结果 DTO
 * <p>
 * 当向量缓存命中时返回的结果对象。
 */
@Data
@AllArgsConstructor
public class CachedResult {

    /**
     * 缓存的上下文文本
     */
    private String context;

    /**
     * 缓存的来源文档列表
     */
    private List<String> sources;

    /**
     * 与查询问题的相似度（0-1之间）
     */
    private double similarity;

    /**
     * 是否来自缓存
     */
    private boolean fromCache;

    public CachedResult(String context, List<String> sources, double similarity) {
        this.context = context;
        this.sources = sources;
        this.similarity = similarity;
        this.fromCache = true;
    }
}
