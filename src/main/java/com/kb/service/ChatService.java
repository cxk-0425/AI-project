package com.kb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat Service - 基于 RAG 上下文的流式问答（支持并发优化）
 * <p>
 * 使用 Spring AI ChatClient 的 stream() 方法，通过 SSE 将 token 逐步推送给前端。
 * 核心优化：
 * <pre>
 * 1. RagService 检索相关上下文（支持向量缓存）
 * 2. 并发检索上下文和来源文档（CompletableFuture）
 * 3. 填充 SystemPrompt（含上下文）
 * 4. ChatClient.stream() 流式生成
 * 5. SSE token-by-token 推送
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final RagService ragService;

    @Qualifier("ragExecutor")
    private final Executor ragExecutor;

    @Value("${kb.chat.system-prompt}")
    private String systemPromptTemplate;

    /**
     * 流式问答：通过 SSE 逐 token 推送给前端（支持并发优化）
     * <p>
     * 优化点：
     * 1. 上下文检索和来源查询并发执行（CompletableFuture）
     * 2. 自动使用向量缓存减少检索延迟
     *
     * @param question   用户问题
     * @param sseEmitter SSE 发射器
     */
    public void streamChat(String question, SseEmitter sseEmitter) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 并发执行：上下文检索 + 来源查询
            CompletableFuture<String> contextFuture = CompletableFuture.supplyAsync(
                    () -> ragService.retrieveAndBuildContext(question),
                    ragExecutor
            );

            CompletableFuture<List<String>> sourcesFuture = CompletableFuture.supplyAsync(
                    () -> ragService.retrieveSourceDocuments(question),
                    ragExecutor
            );

            // 2. 等待并发任务完成
            CompletableFuture.allOf(contextFuture, sourcesFuture).join();
            String context = contextFuture.get();
            List<String> sources = sourcesFuture.get();

            long retrievalTime = System.currentTimeMillis() - startTime;
            log.info("[ChatService] 并发检索完成，问题: {}，来源文档: {}，耗时: {}ms",
                    question, sources, retrievalTime);

            // 3. 填充 SystemPrompt（替换 {context} 占位符）
            String systemPrompt = systemPromptTemplate.replace("{context}", context);

            // 4. 使用 ChatClient 流式生成（Spring AI 1.0 stream API）
            AtomicReference<StringBuilder> fullAnswer = new AtomicReference<>(new StringBuilder());

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .stream()
                    .content()
                    .subscribe(
                            token -> {
                                // 每个 token 推送给前端
                                try {
                                    sseEmitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(token));
                                    fullAnswer.get().append(token);
                                } catch (IOException e) {
                                    log.warn("[ChatService] SSE 发送失败，客户端可能已断开: {}", e.getMessage());
                                    sseEmitter.completeWithError(e);
                                }
                            },
                            error -> {
                                log.error("[ChatService] 流式生成出错", error);
                                try {
                                    sseEmitter.send(SseEmitter.event()
                                            .name("error")
                                            .data("AI 服务暂时不可用，请稍后重试。"));
                                } catch (IOException ex) {
                                    log.warn("[ChatService] 发送错误消息失败", ex);
                                }
                                sseEmitter.completeWithError(error);
                            },
                            () -> {
                                // 流结束，发送引用来源
                                try {
                                    if (!sources.isEmpty()) {
                                        sseEmitter.send(SseEmitter.event()
                                                .name("sources")
                                                .data(String.join(", ", sources)));
                                    }
                                    sseEmitter.send(SseEmitter.event()
                                            .name("done")
                                            .data("[END]"));
                                    sseEmitter.complete();

                                    long totalTime = System.currentTimeMillis() - startTime;
                                    log.info("[ChatService] 回答完成，总长度: {} 字符，总耗时: {}ms",
                                            fullAnswer.get().length(), totalTime);
                                } catch (IOException e) {
                                    sseEmitter.completeWithError(e);
                                }
                            }
                    );

        } catch (Exception e) {
            log.error("[ChatService] Chat 请求失败", e);
            sseEmitter.completeWithError(e);
        }
    }

    /**
     * 同步问答（非流式，用于 API 接口测试）
     *
     * @param question 用户问题
     * @return 完整回答文本
     */
    public String syncChat(String question) {
        String context = ragService.retrieveAndBuildContext(question);
        String systemPrompt = systemPromptTemplate.replace("{context}", context);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
    }
}
