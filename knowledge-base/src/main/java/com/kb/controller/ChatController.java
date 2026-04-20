package com.kb.controller;

import com.kb.service.ChatService;
import com.kb.service.RagService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 问答 Controller
 * <p>
 * 提供：
 * <ul>
 *   <li>GET  /api/chat/stream?q=...  - 流式问答（SSE）</li>
 *   <li>POST /api/chat/sync          - 同步问答（JSON）</li>
 *   <li>POST /api/chat/retrieve      - 仅检索，不生成回答</li>
 * </ul>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;
    private final RagService ragService;

    /** 专用线程池处理 SSE 流式任务（避免阻塞 Web 容器线程） */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 流式问答（SSE）
     * GET /api/chat/stream?q=你的问题
     * <p>
     * 响应事件：
     * - event: token  → 逐 token 输出
     * - event: sources → 引用来源文档名
     * - event: done   → [END] 流结束标记
     * - event: error  → 错误信息
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam @NotBlank(message = "问题不能为空") String q) {

        log.info("[ChatController] 流式问答请求: {}", q);

        SseEmitter emitter = new SseEmitter(180_000L); // 3 分钟超时

        // 在虚拟线程中执行，避免阻塞 Tomcat 线程
        sseExecutor.execute(() -> {
            try {
                chatService.streamChat(q, emitter);
            } catch (Exception e) {
                log.error("[ChatController] SSE 处理异常", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 同步问答（JSON 响应）
     * POST /api/chat/sync
     * Body: { "question": "..." }
     */
    @PostMapping("/sync")
    public ResponseEntity<?> syncChat(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "问题不能为空"));
        }
        try {
            String answer = chatService.syncChat(question);
            List<String> sources = ragService.retrieveSourceDocuments(question);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "question", question,
                    "answer", answer,
                    "sources", sources
            ));
        } catch (Exception e) {
            log.error("[ChatController] 同步问答失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "问答服务异常: " + e.getMessage()
            ));
        }
    }

    /**
     * 仅语义检索（不调用 LLM），用于调试和验证向量库内容
     * POST /api/chat/retrieve
     * Body: { "question": "..." }
     */
    @PostMapping("/retrieve")
    public ResponseEntity<?> retrieve(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "问题不能为空"));
        }
        try {
            var chunks = ragService.retrieveRelevantChunks(question);
            var context = ragService.buildContext(chunks);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "question", question,
                    "chunkCount", chunks.size(),
                    "context", context,
                    "sources", chunks.stream()
                            .map(d -> d.getMetadata().get("filename"))
                            .distinct().toList()
            ));
        } catch (Exception e) {
            log.error("[ChatController] 检索失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "检索失败: " + e.getMessage()
            ));
        }
    }
}
