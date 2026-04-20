package com.kb.controller;

import com.kb.service.VectorCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 缓存管理 Controller
 * <p>
 * 提供向量缓存的统计查询和手动清除功能。
 */
@Slf4j
@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheController {

    private final VectorCacheService vectorCacheService;

    /**
     * 获取缓存统计信息
     * <p>
     * GET /api/cache/stats
     *
     * @return 缓存统计指标
     */
    @GetMapping("/stats")
    public ResponseEntity<VectorCacheService.CacheStats> getCacheStats() {
        VectorCacheService.CacheStats stats = vectorCacheService.getStats();
        log.info("[CacheController] 查询缓存统计 - 大小: {}, 命中率: {:.2f}%",
                stats.currentSize(), stats.hitRate() * 100);
        return ResponseEntity.ok(stats);
    }

    /**
     * 清空所有缓存
     * <p>
     * POST /api/cache/clear
     *
     * @return 操作结果
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearCache() {
        VectorCacheService.CacheStats beforeStats = vectorCacheService.getStats();
        int beforeSize = beforeStats.currentSize();

        vectorCacheService.invalidateAll();

        log.info("[CacheController] 缓存已清空，删除 {} 条记录", beforeSize);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "缓存清空成功",
                "clearedCount", beforeSize
        ));
    }

    /**
     * 清理过期缓存
     * <p>
     * POST /api/cache/evict-expired
     *
     * @return 操作结果
     */
    @PostMapping("/evict-expired")
    public ResponseEntity<Map<String, Object>> evictExpired() {
        VectorCacheService.CacheStats beforeStats = vectorCacheService.getStats();
        int beforeSize = beforeStats.currentSize();

        vectorCacheService.evictExpired();

        VectorCacheService.CacheStats afterStats = vectorCacheService.getStats();
        int afterSize = afterStats.currentSize();
        int removedCount = beforeSize - afterSize;

        log.info("[CacheController] 清理过期缓存完成，删除 {} 条记录", removedCount);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "过期缓存清理完成",
                "removedCount", removedCount,
                "remainingCount", afterSize
        ));
    }
}
