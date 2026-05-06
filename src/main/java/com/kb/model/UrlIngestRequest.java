package com.kb.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * URL 录入请求 DTO
 * <p>
 * 调用 POST /api/documents/ingest/url 时的请求体。
 * 系统将通过 HTTP GET 抓取目标 URL 的内容并将其提取的正文文本录入知识库。
 *
 * <h3>使用示例（curl）</h3>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/documents/ingest/url \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "url": "https://example.com/article",
 *     "storeType": "DOC",
 *     "timeoutSeconds": 15
 *   }'
 * }</pre>
 */
@Data
public class UrlIngestRequest {

    /**
     * 目标 URL（必填）
     * <p>支持 http 和 https 协议，系统发起 GET 请求抓取内容。
     */
    @NotBlank(message = "url 不能为空")
    private String url;

    /**
     * 存储目标：DOC（默认，营销文档库）/ SOP（SOP 活动库）
     */
    private String storeType = "DOC";

    /**
     * HTTP 请求超时时间（秒），默认 15 秒
     */
    private Integer timeoutSeconds = 15;
}
