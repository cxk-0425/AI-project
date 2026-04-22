package com.kb.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 营销活动 API 客户端
 * <p>
 * 将外部 cURL 录入的营销平台接口封装为 Java 方法，供 MarketingMcpTools 调用。
 * 实际项目中应将各接口的 URL/Body Schema 替换为真实值。
 */
@Slf4j
@Component
public class MarketingApiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public MarketingApiClient(
            @Value("${marketing.api.base-url:http://localhost:9090}") String baseUrl,
            @Value("${marketing.api.token:}") String token,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.restClient = builder.build();
    }

    /**
     * 创建营销活动
     * 对应 cURL: POST /api/v1/activities
     */
    public String createActivity(Map<String, Object> params) {
        log.info("[MarketingApiClient] 创建活动，参数: {}", params);
        try {
            String body = objectMapper.writeValueAsString(params);
            String response = restClient.post()
                    .uri("/api/v1/activities")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("[MarketingApiClient] 创建活动成功，响应: {}", response);
            return response;
        } catch (Exception e) {
            log.error("[MarketingApiClient] 创建活动失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询活动列表
     * 对应 cURL: GET /api/v1/activities
     */
    public String listActivities(String activityType, String startDate, String endDate) {
        log.info("[MarketingApiClient] 查询活动列表");
        try {
            String response = restClient.get()
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
                    .body(String.class);
            return response != null ? response : "[]";
        } catch (Exception e) {
            log.error("[MarketingApiClient] 查询活动列表失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 更新营销活动
     * 对应 cURL: PUT /api/v1/activities/{activityId}
     */
    public String updateActivity(String activityId, Map<String, Object> params) {
        log.info("[MarketingApiClient] 更新活动 {}，参数: {}", activityId, params);
        try {
            String body = objectMapper.writeValueAsString(params);
            String response = restClient.put()
                    .uri("/api/v1/activities/{id}", activityId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"success\":true}";
        } catch (Exception e) {
            log.error("[MarketingApiClient] 更新活动失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 启用/禁用营销活动
     * 对应 cURL: PATCH /api/v1/activities/{activityId}/status
     */
    public String toggleActivityStatus(String activityId, boolean enabled) {
        log.info("[MarketingApiClient] 切换活动 {} 状态: {}", activityId, enabled ? "启用" : "禁用");
        try {
            String body = objectMapper.writeValueAsString(Map.of("enabled", enabled));
            String response = restClient.patch()
                    .uri("/api/v1/activities/{id}/status", activityId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"success\":true}";
        } catch (Exception e) {
            log.error("[MarketingApiClient] 切换活动状态失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 创建优惠券
     * 对应 cURL: POST /api/v1/coupons
     */
    public String createCoupon(Map<String, Object> params) {
        log.info("[MarketingApiClient] 创建优惠券，参数: {}", params);
        try {
            String body = objectMapper.writeValueAsString(params);
            String response = restClient.post()
                    .uri("/api/v1/coupons")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"success\":true}";
        } catch (Exception e) {
            log.error("[MarketingApiClient] 创建优惠券失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询活动详情
     * 对应 cURL: GET /api/v1/activities/{activityId}
     */
    public String getActivityDetail(String activityId) {
        log.info("[MarketingApiClient] 查询活动详情: {}", activityId);
        try {
            String response = restClient.get()
                    .uri("/api/v1/activities/{id}", activityId)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{}";
        } catch (Exception e) {
            log.error("[MarketingApiClient] 查询活动详情失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 配置活动规则（满减/折扣等）
     * 对应 cURL: POST /api/v1/activities/{activityId}/rules
     */
    public String configureActivityRules(String activityId, Map<String, Object> rules) {
        log.info("[MarketingApiClient] 配置活动规则 {}，规则: {}", activityId, rules);
        try {
            String body = objectMapper.writeValueAsString(rules);
            String response = restClient.post()
                    .uri("/api/v1/activities/{id}/rules", activityId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return response != null ? response : "{\"success\":true}";
        } catch (Exception e) {
            log.error("[MarketingApiClient] 配置活动规则失败", e);
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
