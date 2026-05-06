package com.kb.token;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Token 自动刷新服务（Redis 缓存 + Redisson 分布式锁）
 * <p>
 * 并发安全保证：
 * <ol>
 *   <li><b>惊群防止</b>：同一时刻只有持有 Redisson 分布式锁的一个实例执行刷新</li>
 *   <li><b>重复刷新防止</b>：持锁后执行双重检查（Double-Check），他人已刷新则直接返回</li>
 *   <li><b>锁竞争优化</b>：未获锁的线程以 500ms 间隔自旋等待（最多 maxRetry 次），
 *       每次自旋后重新从 Redis 读取，而非排队阻塞</li>
 *   <li><b>锁泄漏防护</b>：leaseSeconds 到期自动释放，防止宕机导致锁永久持有</li>
 *   <li><b>提前刷新（Eager Refresh）</b>：Token 剩余有效期 < refreshAheadSeconds（默认5分钟）
 *       时主动触发刷新，避免临界过期导致业务失败</li>
 * </ol>
 * <p>
 * Redis 降级：当 Redis 不可用时降级为本地 synchronized 双重检查刷新，
 * 保证单节点场景下系统继续可用。
 */
@Slf4j
@Service
public class TokenRefreshService {

    private final RedisTemplate<String, TokenInfo> redisTemplate;
    private final RedissonClient redissonClient;
    private final MiddlePlatformTokenClient middlePlatformTokenClient;

    // ─── 配置项 ───────────────────────────────────────────────────────────────

    @Value("${marketing.token.redis-key:marketing:token}")
    private String redisKey;

    @Value("${marketing.token.lock-key:marketing:token:lock}")
    private String lockKey;

    @Value("${marketing.token.lock-wait-seconds:5}")
    private long lockWaitSeconds;

    @Value("${marketing.token.lock-lease-seconds:10}")
    private long lockLeaseSeconds;

    @Value("${marketing.token.refresh-ahead-seconds:300}")
    private long refreshAheadSeconds;

    @Value("${marketing.token.max-retry:3}")
    private int maxRetry;

    // ─── 本地降级缓存（Redis 不可用时使用）─────────────────────────────────────

    /** 本地降级缓存（仅在 Redis 不可用时启用） */
    private volatile TokenInfo localCache = null;

    /** 本地降级锁 */
    private final Object localLock = new Object();

    public TokenRefreshService(
            @Qualifier("tokenRedisTemplate") RedisTemplate<String, TokenInfo> redisTemplate,
            RedissonClient redissonClient,
            MiddlePlatformTokenClient middlePlatformTokenClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.middlePlatformTokenClient = middlePlatformTokenClient;
    }

    /**
     * 获取有效的 Access Token
     * <p>
     * 调用路径：
     * <pre>
     * 1. 快路径：Redis 命中且未进入提前刷新窗口 → 直接返回
     * 2. 慢路径：分布式锁刷新（含 Double-Check）
     * 3. 降级路径：Redis 不可用 → 本地 synchronized 刷新
     * </pre>
     *
     * @return 有效的 Bearer Token 字符串（不含 "Bearer " 前缀）
     * @throws TokenRefreshException 无法获取 Token 时抛出
     */
    public String getValidToken() {
        try {
            return getTokenWithDistributedLock();
        } catch (Exception e) {
            // Redis / Redisson 不可用，降级为本地锁刷新
            log.warn("[TokenRefreshService] 分布式模式不可用，降级为本地锁刷新: {}", e.getMessage());
            return getTokenWithLocalLock();
        }
    }

    /**
     * 主动强制刷新 Token（收到 401 时调用）
     * <p>
     * 清除 Redis 缓存并重新获取，使所有实例在下次请求时都感知到新 Token。
     */
    public void forceRefresh() {
        log.info("[TokenRefreshService] 收到强制刷新信号，清除 Redis Token 缓存");
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("[TokenRefreshService] 清除 Redis 缓存失败，尝试清除本地缓存: {}", e.getMessage());
            localCache = null;
        }
    }

    // ─── 分布式锁刷新路径 ──────────────────────────────────────────────────────

    private String getTokenWithDistributedLock() throws InterruptedException {
        // 1. 快路径：直接读 Redis（无锁）
        TokenInfo cached = readFromRedis();
        if (cached != null && !cached.needsRefresh(refreshAheadSeconds)) {
            log.debug("[TokenRefreshService] 快路径命中 Redis 缓存，token 有效期至: {}",
                    cached.expiresAt());
            return cached.token();
        }

        // 2. 慢路径：自旋等待 + 分布式锁刷新
        log.info("[TokenRefreshService] Token 缺失或即将过期，尝试获取分布式锁刷新...");
        int retries = 0;

        while (retries < maxRetry) {
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);

            if (locked) {
                try {
                    return refreshWithLock();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.debug("[TokenRefreshService] 分布式锁已释放");
                    }
                }
            }

            // 未获锁：等待 500ms 后重新尝试读取（他人可能已完成刷新）
            retries++;
            log.debug("[TokenRefreshService] 未获取到分布式锁，等待 500ms 后第 {}/{} 次重试",
                    retries, maxRetry);
            Thread.sleep(500);

            // 每次自旋后再次读取 Redis（可能他人已完成刷新）
            TokenInfo retryCache = readFromRedis();
            if (retryCache != null && !retryCache.needsRefresh(refreshAheadSeconds)) {
                log.info("[TokenRefreshService] 自旋等待后 Redis 已有新 Token，直接返回");
                return retryCache.token();
            }
        }

        // 3. 超过最大重试次数：降级读取已过期 Token（宁可 401 也不阻塞业务）
        TokenInfo stale = readFromRedis();
        if (stale != null) {
            log.warn("[TokenRefreshService] 超过最大重试次数，返回可能已过期的 Token（将触发 401 后重试）");
            return stale.token();
        }

        throw new TokenRefreshException(
                "无法获取有效的 Access Token：分布式锁竞争失败且 Redis 无缓存");
    }

    /**
     * 持锁后执行 Double-Check 刷新
     */
    private String refreshWithLock() {
        // Double-Check：持锁后再次读 Redis（可能他人在我们等锁期间已完成刷新）
        TokenInfo doubleCheck = readFromRedis();
        if (doubleCheck != null && !doubleCheck.needsRefresh(refreshAheadSeconds)) {
            log.info("[TokenRefreshService] Double-Check 命中，他人已完成刷新，直接返回新 Token");
            return doubleCheck.token();
        }

        // 确实需要刷新：调用中台
        log.info("[TokenRefreshService] Double-Check 未命中，开始调用中台刷新 Token...");
        TokenInfo fresh = middlePlatformTokenClient.fetchToken();

        // 写入 Redis（TTL = expiresIn - 60s，留 60s 缓冲防止时钟偏移）
        long ttlSeconds = Math.max(
                Duration.between(Instant.now(), fresh.expiresAt()).getSeconds() - 60L,
                60L  // 最少保留 60s TTL
        );
        writeToRedis(fresh, ttlSeconds);

        // 同步更新本地降级缓存
        localCache = fresh;

        log.info("[TokenRefreshService] Token 刷新成功，Redis TTL={}s，有效至: {}",
                ttlSeconds, fresh.expiresAt());
        return fresh.token();
    }

    // ─── 本地降级路径（Redis 不可用时）────────────────────────────────────────

    private String getTokenWithLocalLock() {
        // 快路径：本地缓存有效
        if (localCache != null && !localCache.needsRefresh(refreshAheadSeconds)) {
            return localCache.token();
        }

        // 慢路径：本地 synchronized 刷新（单节点场景，无分布式问题）
        synchronized (localLock) {
            // Double-Check
            if (localCache != null && !localCache.needsRefresh(refreshAheadSeconds)) {
                return localCache.token();
            }

            log.info("[TokenRefreshService] 本地降级模式：调用中台刷新 Token");
            TokenInfo fresh = middlePlatformTokenClient.fetchToken();
            localCache = fresh;

            // 尝试回写 Redis（不强制，失败则忽略）
            try {
                long ttlSeconds = Math.max(
                        Duration.between(Instant.now(), fresh.expiresAt()).getSeconds() - 60L,
                        60L
                );
                writeToRedis(fresh, ttlSeconds);
            } catch (Exception e) {
                log.warn("[TokenRefreshService] 回写 Redis 失败（忽略）: {}", e.getMessage());
            }

            return fresh.token();
        }
    }

    // ─── Redis 操作工具方法 ────────────────────────────────────────────────────

    private TokenInfo readFromRedis() {
        try {
            return redisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            log.warn("[TokenRefreshService] 读取 Redis Token 缓存失败: {}", e.getMessage());
            return null;
        }
    }

    private void writeToRedis(TokenInfo tokenInfo, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(redisKey, tokenInfo, ttlSeconds, TimeUnit.SECONDS);
            log.debug("[TokenRefreshService] Token 写入 Redis 成功，TTL={}s", ttlSeconds);
        } catch (Exception e) {
            log.error("[TokenRefreshService] Token 写入 Redis 失败: {}", e.getMessage());
        }
    }
}
