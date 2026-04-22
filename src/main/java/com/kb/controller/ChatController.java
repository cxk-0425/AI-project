package com.kb.controller;

import com.kb.agent.ConfigAgentOrchestrator;
import com.kb.intent.IntentClassifierService;
import com.kb.intent.IntentResult;
import com.kb.intent.IntentType;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 问答 Controller（含意图路由）
 * <p>
 * 提供：
 * <ul>
 *   <li>GET  /api/chat/stream?q=...  - 流式问答（SSE，支持意图路由）</li>
 *   <li>POST /api/chat/sync          - 同步问答（JSON，仅 QUERY 路径）</li>
 *   <li>POST /api/chat/retrieve      - 仅检索，不生成回答</li>
 * </ul>
 * <p>
 * 意图路由流程：
 * <pre>
 * 用户 Query
 *   └─▶ IntentClassifierService（BERT + LLM 降级）
 *         ├─ QUERY  ──▶ ChatService.streamChat（原有 RAG 问答路径）
 *         └─ CONFIG ──▶ ConfigAgentOrchestrator（SOP → Planner → Supervisor → Skill → MCP 调用）
 * </pre>
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
    private final IntentClassifierService intentClassifierService;
    private final ConfigAgentOrchestrator configAgentOrchestrator;

    /** 专用线程池处理 SSE 流式任务（避免阻塞 Web 容器线程） */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 流式问答（SSE，含意图路由）
     * GET /api/chat/stream?q=你的问题
     * <p>
     * 响应事件（公共）：
     * <ul>
     *   <li>event: intent      → 意图识别结果 {"type":"QUERY|CONFIG","confidence":0.95,"source":"BERT|LLM|FALLBACK"}</li>
     * </ul>
     * QUERY 路径：
     * <ul>
     *   <li>event: token   → 逐 token 输出</li>
     *   <li>event: sources → 引用来源文档名</li>
     *   <li>event: done    → [END] 流结束标记</li>
     * </ul>
     * CONFIG 路径：
     * <ul>
     *   <li>event: sop-sources   → SOP 来源文档</li>
     *   <li>event: plan          → 执行步骤计划 JSON 数组</li>
     *   <li>event: agent-status  → Agent 各阶段进度</li>
     *   <li>event: step-start    → 步骤开始 {"stepId":1,"action":"createActivity"}</li>
     *   <li>event: step-result   → 步骤结果 {"stepId":1,"result":"...","success":true}</li>
     *   <li>event: agent-done    → Agent 链全部完成</li>
     * </ul>
     * 公共异常：
     * <ul>
     *   <li>event: error → 错误信息</li>
     * </ul>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam @NotBlank(message = "问题不能为空") String q) {

        log.info("[ChatController] 流式问答请求: {}", q);

        SseEmitter emitter = new SseEmitter(180_000L); // 3 分钟超时

        // 在虚拟线程中执行，避免阻塞 Tomcat 线程
        sseExecutor.execute(() -> {
            try {
                // ===== 意图识别 =====
                IntentResult intent = intentClassifierService.classify(q);
                log.info("[ChatController] 意图识别结果: {}", intent);

                // 推送意图事件给前端
                sendIntentEvent(emitter, intent);

                // ===== 意图路由 =====
                if (intent.getType() == IntentType.CONFIG) {
                    // 配置类：走 Agent 链（SOP → Planner → Supervisor → Skill → MCP）
                    log.info("[ChatController] 路由到 CONFIG Agent 链");
                    configAgentOrchestrator.execute(q, emitter);
                } else {
                    // 查询类（QUERY / UNKNOWN 默认查询）：走原有 RAG 问答路径
                    log.info("[ChatController] 路由到 QUERY RAG 路径，意图类型: {}", intent.getType());
                    chatService.streamChat(q, emitter);
                }

            } catch (Exception e) {
                log.error("[ChatController] SSE 处理异常", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /** 推送意图识别结果 SSE 事件 */
    private void sendIntentEvent(SseEmitter emitter, IntentResult intent) {
        try {
            String data = String.format(
                    "{\"type\":\"%s\",\"confidence\":%.3f,\"source\":\"%s\"}",
                    intent.getType().name(),
                    intent.getConfidence(),
                    intent.getSource().name()
            );
            emitter.send(SseEmitter.event().name("intent").data(data));
        } catch (IOException e) {
            log.warn("[ChatController] 发送 intent 事件失败: {}", e.getMessage());
        }
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
