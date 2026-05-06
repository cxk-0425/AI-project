package com.kb.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.token.TokenRefreshException;
import com.kb.token.TokenRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 营销活动 API 客户端
 * <p>
 * 将外部营销平台接口封装为 Java 方法，供 MarketingMcpTools 调用。
 * <p>
 * <b>鉴权模式（改造后）</b>：
 * <ul>
 *   <li>每次请求前通过 {@link TokenRefreshService} 动态获取 Access Token</li>
 *   <li>Token 由 Redis 缓存，支持提前刷新（TTL - 5分钟）</li>
 *   <li>多实例并发刷新由 Redisson 分布式锁保护，避免惊群和重复刷新</li>
 *   <li>收到 401 时自动触发强制刷新并重试一次</li>
 * </ul>
 */
@Slf4j
@Component
public class MarketingApiClient {

    /** 无状态 RestClient 基础构建器（不携带 Token，动态注入） */
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final TokenRefreshService tokenRefreshService;

    public MarketingApiClient(
            @Value("${marketing.api.base-url:http://localhost:9090}") String baseUrl,
            ObjectMapper objectMapper,
            TokenRefreshService tokenRefreshService) {
        this.objectMapper = objectMapper;
        this.tokenRefreshService = tokenRefreshService;
        this.restClientBuilder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                // 请求超时配置：连接 5s，读取 30s（营销接口可能较慢）
                .requestFactory(buildHttpClientFactory());
    }

    // ─── 对外业务接口 ──────────────────────────────────────────────────────────

    /**
     * 创建营销活动
     * 对应 cURL: POST /api/v1/activities
     */
    public String createActivity(Map<String, Object> params) {
        log.info("[MarketingApiClient] 创建活动，参数: {}", params);
        return executeWithTokenRetry("createActivity", () -> {
            String body = objectMapper.writeValueAsString(params);
            return restClientWithToken().post()
                    .uri("/api/v1/activities")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        });
    }

    /**
     * 查询活动列表
     * 对应 cURL: GET /api/v1/activities
     */
    public String listActivities(String activityType, String startDate, String endDate) {
        log.info("[MarketingApiClient] 查询活动列表，type={}, start={}, end={}",
                activityType, startDate, endDate);
        return executeWithTokenRetry("listActivities", () ->
                restClientWithToken().get()
                        .uri(uriBuilder -> {
                            var b = uriBuilder.path("/api/v1/activities");
                            if (activityType != null && !activityType.isBlank())
                                b.queryParam("type", activityType);
                            if (startDate != null && !startDate.isBlank())
                                b.queryParam("startDate", startDate);
                            if (endDate != null && !endDate.isBlank())
                                b.queryParam("endDate", endDate);
                            return b.build();
                        })
                        .retrieve()
                        .body(String.class)
        );
    }

    /**
     * 更新营销活动
     * 对应 cURL: PUT /api/v1/activities/{activityId}
     */
    public String updateActivity(String activityId, Map<String, Object> params) {
        log.info("[MarketingApiClient] 更新活动 {}，参数: {}", activityId, params);
        return executeWithTokenRetry("updateActivity", () -> {
            String body = objectMapper.writeValueAsString(params);
            return restClientWithToken().put()
                    .uri("/api/v1/activities/{id}", activityId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        });
    }

    /**
     * 启用/禁用营销活动
     * 对应 cURL: PATCH /api/v1/activities/{activityId}/status
     */
    public String toggleActivityStatus(String activityId, boolean enabled) {
        log.info("[MarketingApiClient] 切换活动 {} 状态: {}", activityId, enabled ? "启用" : "禁用");
        return executeWithTokenRetry("toggleActivityStatus", () -> {
            String body = objectMapper.writeValueAsString(Map.of("enabled", enabled));
            return restClientWithToken().patch()
                    .uri("/api/v1/activities/{id}/status", activityId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        });
    }

    /**
     * 创建优惠券
     * 对应 cURL: POST /api/v1/coupons
     */
    public String createCoupon(Map<String, Object> params) {
        log.info("[MarketingApiClient] 创建优惠券，参数: {}", params);
        return executeWithTokenRetry("createCoupon", () -> {
            String body = objectMapper.writeValueAsString(params);
            return restClientWithToken().post()
                    .uri("/api/v1/coupons")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        });
    }

    /**
     * 查询活动详情
     * 对应 cURL: GET /api/v1/activities/{activityId}
     */
    public String getActivityDetail(String activityId) {
        log.info("[MarketingApiClient] 查询活动详情: {}", activityId);
        return executeWithTokenRetry("getActivityDetail", () ->
                restClientWithToken().get()
                        .uri("/api/v1/activities/{id}", activityId)
                        .retrieve()
                        .body(String.class)
        );
    }

    /**
     * 配置活动规则（满减/折扣等）
     * 对应 cURL: POST /api/v1/activities/{activityId}/rules
     */
    public String configureActivityRules(String activityId, Map<String, Object> rules) {
        log.info("[MarketingApiClient] 配置活动规则 {}，规则: {}", activityId, rules);
        return executeWithTokenRetry("configureActivityRules", () -> {
            String body = objectMapper.writeValueAsString(rules);
            return restClientWithToken().post()
                    .uri("/api/v1/activities/{id}/rules", activityId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        });
    }

    // ─── 核心机制：Token 注入 + 401 自动重试 ────────────────────────────────────

    /**
     * 构建携带当前有效 Token 的 RestClient 实例
     * <p>
     * 通过 {@code mutate()} 在已有 Builder 基础上覆盖 Authorization Header，
     * 避免每次重新配置 baseUrl 和超时等公共参数。
     */
    private RestClient restClientWithToken() {
        String token = tokenRefreshService.getValidToken();
        return restClientBuilder
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    /**
     * 执行带 Token 刷新重试的 HTTP 请求
     * <p>
     * 策略：
     * <ol>
     *   <li>首次正常执行，使用当前有效 Token</li>
     *   <li>若响应 401（Token 在响应途中过期），触发 {@link TokenRefreshService#forceRefresh()} 并重试一次</li>
     *   <li>重试仍失败则返回错误 JSON，不再继续</li>
     * </ol>
     *
     * @param operation 操作名称（仅用于日志）
     * @param supplier  实际 HTTP 调用逻辑
     * @return 接口响应 JSON 字符串，失败时返回 {"success":false,"message":"..."}
     */
    private String executeWithTokenRetry(String operation, CheckedSupplier<String> supplier) {
        try {
            String result = supplier.get();
            log.info("[MarketingApiClient] {} 成功", operation);
            return result != null ? result : "{\"success\":true}";
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                // 收到 401：强制刷新 Token 后重试一次
                log.warn("[MarketingApiClient] {} 收到 401，强制刷新 Token 后重试...", operation);
                tokenRefreshService.forceRefresh();
                try {
                    String result = supplier.get();
                    log.info("[MarketingApiClient] {} 重试成功", operation);
                    return result != null ? result : "{\"success\":true}";
                } catch (Exception retryEx) {
                    log.error("[MarketingApiClient] {} 重试仍失败: {}", operation, retryEx.getMessage());
                    return errorJson(retryEx.getMessage());
                }
            }
            log.error("[MarketingApiClient] {} HTTP 错误: {} {}", operation,
                    e.getStatusCode(), e.getMessage());
            return errorJson(e.getMessage());
        } catch (TokenRefreshException e) {
            log.error("[MarketingApiClient] {} Token 刷新失败: {}", operation, e.getMessage());
            return errorJson("Token 刷新失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[MarketingApiClient] {} 执行异常", operation, e);
            return errorJson(e.getMessage());
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    private String errorJson(String message) {
        String safeMsg = message == null ? "unknown error" :
                message.replace("\"", "'").replace("\n", " ");
        return "{\"success\":false,\"message\":\"" + safeMsg + "\"}";
    }

    private org.springframework.http.client.ClientHttpRequestFactory buildHttpClientFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return factory;
    }

    /**
     * 可抛受检异常的函数式接口（用于 lambda 中调用抛出 Exception 的方法）
     */
    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
