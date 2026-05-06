package com.kb.token;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Access Token 值对象（Redis 序列化存储）
 * <p>
 * 不可变对象，存储从中台获取的 token 及其过期时间。
 * 通过 {@link #needsRefresh(long)} 判断是否需要主动刷新。
 */
public record TokenInfo(
        String token,
        Instant expiresAt,
        Instant createdAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Token 是否已过期
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Token 是否需要刷新（已过期 或 剩余有效期 < refreshAheadSeconds）
     *
     * @param refreshAheadSeconds 提前刷新窗口（秒），典型值 300（5 分钟）
     */
    public boolean needsRefresh(long refreshAheadSeconds) {
        return Instant.now().isAfter(expiresAt.minusSeconds(refreshAheadSeconds));
    }

    /**
     * 构建 TokenInfo，expiresIn 为从当前时刻起的有效秒数
     *
     * @param token     access token 字符串
     * @param expiresIn 有效期（秒），从中台响应的 expires_in 字段
     */
    public static TokenInfo of(String token, long expiresIn) {
        Instant now = Instant.now();
        return new TokenInfo(token, now.plusSeconds(expiresIn), now);
    }
}
