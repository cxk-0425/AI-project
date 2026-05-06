package com.kb.token;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 中台 Access Token 获取客户端
 * <p>
 * 通过调用中台 OAuth 接口（Client Credentials 模式）获取 Access Token。
 * <p>
 * 接口约定：
 * <pre>
 * POST {middle-platform-url}/oauth/token
 * Content-Type: application/json
 * Request:  { "appId": "...", "appSecret": "...", "grantType": "client_credentials" }
 * Response: { "accessToken": "...", "expiresIn": 7200, "tokenType": "Bearer" }
 * </pre>
 */
@Slf4j
@Component
public class MiddlePlatformTokenClient {

    private final RestClient restClient;

    @Value("${marketing.token.app-id:your-app-id}")
    private String appId;

    @Value("${marketing.token.app-secret:your-app-secret}")
    private String appSecret;

    public MiddlePlatformTokenClient(
            @Value("${marketing.token.middle-platform-url:http://localhost:8888}") String middlePlatformUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(middlePlatformUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                // 中台接口超时配置：连接 5s，读取 10s
                .requestFactory(buildHttpClientWithTimeout())
                .build();
    }

    /**
     * 从中台获取新的 Access Token
     *
     * @return TokenInfo（包含 token 字符串和过期时间）
     * @throws TokenRefreshException 中台接口调用失败时抛出
     */
    public TokenInfo fetchToken() {
        log.info("[MiddlePlatformTokenClient] 开始从中台获取 Access Token，appId={}", appId);

        try {
            TokenRequest request = new TokenRequest(appId, appSecret, "client_credentials");

            TokenResponse response = restClient.post()
                    .uri("/oauth/token")
                    .body(request)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new TokenRefreshException("中台返回空 token 响应");
            }

            // expiresIn 默认 7200 秒（2小时），实际以中台返回值为准
            long expiresIn = response.expiresIn() > 0 ? response.expiresIn() : 7200L;
            TokenInfo tokenInfo = TokenInfo.of(response.accessToken(), expiresIn);

            log.info("[MiddlePlatformTokenClient] Access Token 获取成功，有效期 {} 秒（约 {} 分钟）",
                    expiresIn, expiresIn / 60);

            return tokenInfo;

        } catch (TokenRefreshException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MiddlePlatformTokenClient] 从中台获取 Access Token 失败", e);
            throw new TokenRefreshException("调用中台 OAuth 接口失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建带超时配置的 HttpClient 工厂
     * ConnectTimeout: 5s，ReadTimeout: 10s
     */
    private org.springframework.http.client.ClientHttpRequestFactory buildHttpClientWithTimeout() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return factory;
    }

    // ─── 内部 DTO ─────────────────────────────────────────────────────────────

    /**
     * 请求体：中台 OAuth 接口入参
     */
    private record TokenRequest(
            @JsonProperty("appId") String appId,
            @JsonProperty("appSecret") String appSecret,
            @JsonProperty("grantType") String grantType
    ) {}

    /**
     * 响应体：中台 OAuth 接口返回
     */
    private record TokenResponse(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("expiresIn") long expiresIn,
            @JsonProperty("tokenType") String tokenType
    ) {}
}
