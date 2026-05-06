package com.kb.parser;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * URL 内容抓取工具
 * <p>
 * 通过 JDK HttpClient 发起 GET 请求，根据响应 Content-Type 自动选择解析策略：
 * <ul>
 *   <li>{@code text/html} → Jsoup 提取 HTML 正文文本，去除导航、广告等无关区域</li>
 *   <li>{@code text/plain} / {@code text/markdown} → 直接返回原始文本</li>
 *   <li>其他类型 → 尝试 toString 读取文本内容</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * UrlFetcher.FetchResult result = urlFetcher.fetch("https://example.com/article", 15);
 * String title   = result.title();   // 页面标题（HTML title 标签）
 * String content = result.content(); // 提取的正文文本
 * }</pre>
 */
@Slf4j
@Component
public class UrlFetcher {

    /** 允许的 URL scheme，防止 SSRF */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Value("${kb.ingest.url.user-agent:Mozilla/5.0 (compatible; KnowledgeBase-Bot/1.0)}")
    private String userAgent;

    @Value("${kb.ingest.url.max-content-bytes:5242880}") // 默认 5 MB
    private long maxContentBytes;

    /**
     * 抓取目标 URL 的文本内容
     *
     * @param url            目标 URL（必须 http/https）
     * @param timeoutSeconds HTTP 请求超时（秒）
     * @return {@link FetchResult} 包含标题和正文内容
     * @throws IOException              网络请求异常
     * @throws IllegalArgumentException URL 非法或 scheme 不允许
     */
    public FetchResult fetch(String url, int timeoutSeconds) throws IOException, InterruptedException {
        // 1. 校验 URL scheme（防 SSRF）
        URI uri = parseAndValidateUri(url);

        log.info("[UrlFetcher] 开始抓取 URL: {}（超时 {}s）", url, timeoutSeconds);

        // 2. 构建 HTTP 请求
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,text/plain,text/markdown,*/*;q=0.9")
                .header("Accept-Charset", "UTF-8,utf-8;q=0.9,*;q=0.7")
                .GET()
                .build();

        // 3. 发送请求
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        log.info("[UrlFetcher] HTTP {} <- {}", statusCode, url);

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP 请求失败，状态码: " + statusCode + "，URL: " + url);
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new IOException("URL 响应内容为空: " + url);
        }

        // 4. 按 Content-Type 解析内容
        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("text/html")
                .toLowerCase();

        FetchResult result;
        if (contentType.contains("text/html")) {
            result = parseHtml(body, url);
        } else if (contentType.contains("text/plain") || contentType.contains("text/markdown")) {
            result = new FetchResult(uri.getHost(), body.trim());
        } else {
            log.warn("[UrlFetcher] 未知 Content-Type: {}，尝试按纯文本处理", contentType);
            result = new FetchResult(uri.getHost(), body.trim());
        }

        // 5. 内容大小安全检查
        if (result.content().getBytes().length > maxContentBytes) {
            String truncated = new String(result.content().getBytes(), 0, (int) maxContentBytes);
            log.warn("[UrlFetcher] 内容超过 {} 字节，已截断（URL: {}）", maxContentBytes, url);
            return new FetchResult(result.title(), truncated);
        }

        log.info("[UrlFetcher] 抓取完成，标题: '{}'，内容: {} 字符", result.title(), result.content().length());
        return result;
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────────────

    /**
     * 使用 Jsoup 解析 HTML，提取页面标题和正文文本
     * <p>
     * 解析策略：
     * <ol>
     *   <li>先尝试提取 {@code <article>}、{@code <main>} 等语义标签</li>
     *   <li>回退到 {@code <body>} 全文</li>
     *   <li>清理脚本、样式、导航等噪音标签</li>
     * </ol>
     */
    private FetchResult parseHtml(String html, String url) {
        Document doc = Jsoup.parse(html, url);

        // 提取标题
        String title = doc.title();
        if (title == null || title.isBlank()) {
            title = doc.select("h1").first() != null
                    ? doc.select("h1").first().text()
                    : URI.create(url).getHost();
        }

        // 移除干扰元素：导航、侧边栏、页脚、广告、脚本、样式
        doc.select("nav, header, footer, aside, script, style, iframe, " +
                   "noscript, .nav, .navigation, .sidebar, .advertisement, " +
                   ".ad, .ads, .cookie, .popup, .modal, .banner").remove();

        // 优先提取语义主体区域
        String bodyText;
        var article = doc.selectFirst("article");
        var main = doc.selectFirst("main");
        var content = doc.selectFirst("#content, .content, #main-content, .post-content, .article-body");

        if (article != null) {
            bodyText = article.text();
        } else if (main != null) {
            bodyText = main.text();
        } else if (content != null) {
            bodyText = content.text();
        } else {
            // 回退：取 body 全文
            bodyText = doc.body() != null ? doc.body().text() : doc.text();
        }

        // 去除多余空白行和首尾空格
        bodyText = bodyText.replaceAll("\\s{3,}", "\n\n").trim();

        return new FetchResult(title.trim(), bodyText);
    }

    /**
     * 解析并校验 URI，防 SSRF
     */
    private URI parseAndValidateUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 URL 格式: " + url, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException(
                    "不支持的 URL scheme: " + scheme + "（仅允许 http/https）");
        }

        return uri;
    }

    // ─── 返回值 ───────────────────────────────────────────────────────────────

    /**
     * URL 抓取结果
     *
     * @param title   页面标题（用于 kb_document.filename）
     * @param content 提取的正文文本（用于切块和向量化）
     */
    public record FetchResult(String title, String content) {
    }
}
