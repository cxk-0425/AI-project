package com.kb.token;

/**
 * Token 刷新异常
 * <p>
 * 在以下场景抛出：
 * <ul>
 *   <li>中台 OAuth 接口调用失败（网络错误、5xx 等）</li>
 *   <li>达到最大自旋重试次数后仍无法获取有效 Token</li>
 *   <li>中台返回空 token 或非法响应</li>
 * </ul>
 */
public class TokenRefreshException extends RuntimeException {

    public TokenRefreshException(String message) {
        super(message);
    }

    public TokenRefreshException(String message, Throwable cause) {
        super(message, cause);
    }
}
