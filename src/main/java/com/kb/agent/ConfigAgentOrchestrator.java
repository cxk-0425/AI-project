package com.kb.agent;

import com.kb.service.SopRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * 配置类操作 Agent 编排器（Orchestrator）
 * <p>
 * 当意图识别结果为 CONFIG（配置类）时，由此类负责完整的 Agent 链路调度：
 * <ol>
 *   <li>SOP RAG 检索：从 SOP 文档库中获取相关操作流程上下文</li>
 *   <li>Planner Agent：根据用户需求 + SOP 上下文，生成有序执行步骤</li>
 *   <li>Supervisor Agent：校验步骤完整性，发现缺失则触发重新规划</li>
 *   <li>Skill Agent：逐步执行 MCP 工具调用，通过 SSE 实时推送进度</li>
 * </ol>
 *
 * 上游：{@link com.kb.controller.ChatController}（意图路由后进入）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigAgentOrchestrator {

    private final SopRagService sopRagService;
    private final PlannerAgent plannerAgent;
    private final SupervisorAgent supervisorAgent;
    private final SkillAgent skillAgent;

    @Value("${agent.supervisor.max-replan-count:2}")
    private int maxReplanCount;

    /**
     * 执行完整的配置类 Agent 链路
     *
     * @param userQuery 用户的配置类原始请求
     * @param emitter   SSE 推送通道（由 ChatController 创建）
     */
    public void execute(String userQuery, SseEmitter emitter) {
        log.info("[ConfigAgentOrchestrator] 开始执行配置类 Agent 链路，query={}", userQuery);

        try {
            // ======== Step 1: SOP RAG 检索 ========
            sendStatusEvent(emitter, "rag", "正在检索 SOP 操作流程...");
            String sopContext = sopRagService.retrieveAndBuildSopContext(userQuery);
            List<String> sopSources = sopRagService.retrieveSopSources(userQuery);

            log.info("[ConfigAgentOrchestrator] SOP 检索完成，来源文档: {}", sopSources);
            sendSopSourcesEvent(emitter, sopSources);

            // ======== Step 2: Planner Agent — 步骤拆分 ========
            sendStatusEvent(emitter, "planner", "正在生成执行计划...");
            List<PlanStep> steps = plannerAgent.plan(userQuery, sopContext);

            if (steps.isEmpty()) {
                log.warn("[ConfigAgentOrchestrator] Planner 未生成任何步骤");
                sendErrorEvent(emitter, "无法为该请求生成执行计划，请重新描述您的需求");
                return;
            }

            sendPlanEvent(emitter, steps);
            log.info("[ConfigAgentOrchestrator] Planner 生成 {} 个步骤", steps.size());

            // ======== Step 3: Supervisor Agent — 步骤校验 + 重规划 ========
            sendStatusEvent(emitter, "supervisor", "正在审核执行计划完整性...");

            int replanCount = 0;
            ValidationResult validation = supervisorAgent.validate(userQuery, steps);

            while (!validation.isComplete() && replanCount < maxReplanCount) {
                replanCount++;
                log.info("[ConfigAgentOrchestrator] Supervisor 发现缺失步骤，第 {} 次重规划", replanCount);
                sendStatusEvent(emitter, "replan",
                        String.format("发现缺失步骤，正在补充（第 %d/%d 次）...", replanCount, maxReplanCount));

                steps = plannerAgent.replan(userQuery, sopContext, validation.missingSteps());
                sendPlanEvent(emitter, steps);
                validation = supervisorAgent.validate(userQuery, steps);
            }

            if (!validation.isComplete()) {
                log.warn("[ConfigAgentOrchestrator] 达到最大重规划次数，强制继续执行");
                sendStatusEvent(emitter, "supervisor-warn",
                        "步骤审核未完全通过，继续尝试执行...");
            } else {
                sendStatusEvent(emitter, "supervisor-pass", "执行计划审核通过");
                log.info("[ConfigAgentOrchestrator] Supervisor 校验通过，准备执行");
            }

            // ======== Step 4: Skill Agent — MCP 工具调用执行 ========
            sendStatusEvent(emitter, "executing", "开始执行操作步骤...");
            skillAgent.execute(steps, emitter);

        } catch (Exception e) {
            log.error("[ConfigAgentOrchestrator] Agent 链路执行异常", e);
            sendErrorEvent(emitter, "执行过程中发生异常：" + e.getMessage());
        }
    }

    // ======================== SSE 事件推送工具方法 ========================

    private void sendStatusEvent(SseEmitter emitter, String stage, String message) {
        try {
            String data = """
                    {"stage":"%s","message":"%s"}
                    """.formatted(stage, escapeJson(message)).trim();
            emitter.send(SseEmitter.event().name("agent-status").data(data));
        } catch (IOException e) {
            log.warn("[ConfigAgentOrchestrator] 发送 agent-status 事件失败: {}", e.getMessage());
        }
    }

    private void sendSopSourcesEvent(SseEmitter emitter, List<String> sources) {
        try {
            String sourcesJson = sources.stream()
                    .map(s -> "\"" + escapeJson(s) + "\"")
                    .reduce((a, b) -> a + "," + b)
                    .map(s -> "[" + s + "]")
                    .orElse("[]");
            emitter.send(SseEmitter.event().name("sop-sources").data(sourcesJson));
        } catch (IOException e) {
            log.warn("[ConfigAgentOrchestrator] 发送 sop-sources 事件失败", e);
        }
    }

    private void sendPlanEvent(SseEmitter emitter, List<PlanStep> steps) {
        try {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                sb.append("""
                        {"stepId":%d,"action":"%s","description":"%s"}
                        """.formatted(step.stepId(), step.action(),
                        escapeJson(step.description())).trim());
                if (i < steps.size() - 1) sb.append(",");
            }
            sb.append("]");
            emitter.send(SseEmitter.event().name("plan").data(sb.toString()));
        } catch (IOException e) {
            log.warn("[ConfigAgentOrchestrator] 发送 plan 事件失败", e);
        }
    }

    private void sendErrorEvent(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data("{\"message\":\"" + escapeJson(errorMessage) + "\"}"));
            emitter.complete();
        } catch (IOException e) {
            log.warn("[ConfigAgentOrchestrator] 发送 error 事件失败", e);
            emitter.completeWithError(e);
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
