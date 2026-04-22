package com.kb.agent;

import com.kb.mcp.MarketingMcpTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * Skill Agent — MCP 工具参数提取 + Tool Calling 执行
 * <p>
 * 职责：
 * <ol>
 *   <li>接收由 PlannerAgent 生成的步骤列表</li>
 *   <li>对每个步骤，调用绑定了 MCP 工具的 ChatClient，触发 Tool Calling</li>
 *   <li>将每个步骤的执行结果通过 SSE 推送到前端</li>
 * </ol>
 * <p>
 * 核心机制：将 {@link MarketingMcpTools} 中的 @Tool 方法注册到 ChatClient，
 * LLM 会根据步骤 description 自主选择并调用正确的工具，完成参数绑定。
 *
 * 上游：{@link ConfigAgentOrchestrator}
 */
@Slf4j
@Service
public class SkillAgent {

    /** 绑定了 MCP 工具的专用 ChatClient */
    private final ChatClient skillChatClient;

    public SkillAgent(ChatClient.Builder builder, MarketingMcpTools mcpTools) {
        this.skillChatClient = builder
                .defaultTools(mcpTools) // 注册所有 @Tool 方法
                .build();
    }

    /**
     * 按步骤顺序执行 MCP 工具调用，并通过 SSE 实时推送进度
     *
     * @param steps   PlannerAgent 生成且经 SupervisorAgent 验证的步骤列表
     * @param emitter SSE 推送通道
     */
    public void execute(List<PlanStep> steps, SseEmitter emitter) {
        log.info("[SkillAgent] 开始执行 {} 个步骤", steps.size());

        for (PlanStep step : steps) {
            log.info("[SkillAgent] 执行步骤 {}: action={}, description={}",
                    step.stepId(), step.action(), step.description());

            // Step 开始事件
            sendStepStartEvent(emitter, step);

            try {
                // 调用 LLM + Tool Calling 执行当前步骤
                String result = executeStep(step);

                // Step 结果事件
                sendStepResultEvent(emitter, step.stepId(), result, true);

            } catch (Exception e) {
                log.error("[SkillAgent] 步骤 {} 执行失败", step.stepId(), e);
                sendStepResultEvent(emitter, step.stepId(), "执行失败: " + e.getMessage(), false);
                // 遇到错误继续执行后续步骤（可根据业务需求改为中断）
            }
        }

        // 所有步骤完成
        sendDoneEvent(emitter, steps.size());
    }

    // ======================== 私有方法 ========================

    private String executeStep(PlanStep step) {
        String stepInstruction = buildStepInstruction(step);
        log.debug("[SkillAgent] 步骤 {} 指令: {}", step.stepId(), stepInstruction);

        return skillChatClient.prompt()
                .system("""
                        你是一个营销活动配置助手，需要调用工具来完成具体操作。
                        
                        规则：
                        1. 根据步骤描述，选择最合适的工具并调用
                        2. 从步骤描述和参数中提取工具所需的参数值
                        3. 如果某个参数在描述中未明确提及，使用合理的默认值
                        4. 调用工具后，返回简洁的执行摘要（不超过100字）
                        5. 摘要格式：[成功/失败] 操作描述，如有ID则附上
                        """)
                .user(stepInstruction)
                .call()
                .content();
    }

    private String buildStepInstruction(PlanStep step) {
        StringBuilder sb = new StringBuilder();
        sb.append("请执行以下操作步骤：\n\n");
        sb.append("步骤 ").append(step.stepId()).append(": ").append(step.description()).append("\n");

        if (step.params() != null && !step.params().isEmpty()) {
            sb.append("\n已知参数：\n");
            step.params().forEach((k, v) ->
                    sb.append("  - ").append(k).append(": ").append(v).append("\n")
            );
        }

        if (step.hasDependencies()) {
            sb.append("\n注意：此步骤依赖步骤 ").append(step.dependsOn()).append(" 的执行结果");
        }

        return sb.toString();
    }

    private void sendStepStartEvent(SseEmitter emitter, PlanStep step) {
        try {
            String eventData = """
                    {"stepId":%d,"action":"%s","description":"%s","status":"running"}
                    """.formatted(
                    step.stepId(),
                    step.action(),
                    escapeJson(step.description())
            ).trim();

            emitter.send(SseEmitter.event()
                    .name("step-start")
                    .data(eventData));
        } catch (IOException e) {
            log.warn("[SkillAgent] 发送 step-start 事件失败", e);
        }
    }

    private void sendStepResultEvent(SseEmitter emitter, int stepId, String result, boolean success) {
        try {
            String eventData = """
                    {"stepId":%d,"result":"%s","success":%s}
                    """.formatted(
                    stepId,
                    escapeJson(result),
                    success
            ).trim();

            emitter.send(SseEmitter.event()
                    .name("step-result")
                    .data(eventData));
        } catch (IOException e) {
            log.warn("[SkillAgent] 发送 step-result 事件失败", e);
        }
    }

    private void sendDoneEvent(SseEmitter emitter, int totalSteps) {
        try {
            emitter.send(SseEmitter.event()
                    .name("agent-done")
                    .data("""
                            {"message":"所有步骤执行完成","totalSteps":%d}
                            """.formatted(totalSteps).trim()));
            emitter.complete();
        } catch (IOException e) {
            log.warn("[SkillAgent] 发送 agent-done 事件失败", e);
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
