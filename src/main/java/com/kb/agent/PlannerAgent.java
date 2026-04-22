package com.kb.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Planner Agent — 统一表述 + 步骤拆分
 * <p>
 * 职责：
 * <ol>
 *   <li>接收用户意图（配置类 query）和 SOP 上下文</li>
 *   <li>理解用户目标，从 SOP 流程中提取可执行步骤</li>
 *   <li>将每个步骤对应到可用的 MCP 工具（如 createActivity、createCoupon）</li>
 *   <li>返回有序的 {@link PlanStep} 列表</li>
 * </ol>
 *
 * 上游：{@link ConfigAgentOrchestrator}
 * 下游：{@link SupervisorAgent} 负责验证步骤完整性
 */
@Slf4j
@Service
public class PlannerAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Value("${agent.planner.max-steps:10}")
    private int maxSteps;

    public PlannerAgent(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据用户意图和 SOP 上下文生成执行计划
     *
     * @param userQuery  用户的配置类请求
     * @param sopContext SopRagService 检索到的操作流程参考上下文
     * @return 有序步骤列表
     */
    public List<PlanStep> plan(String userQuery, String sopContext) {
        log.info("[PlannerAgent] 开始生成执行计划，query={}", userQuery);

        String systemPrompt = buildPlannerSystemPrompt(sopContext);

        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(buildUserPrompt(userQuery))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[PlannerAgent] LLM 调用失败", e);
            return Collections.emptyList();
        }

        log.debug("[PlannerAgent] LLM 原始响应: {}", response);
        return parseSteps(response, userQuery);
    }

    /**
     * 基于 Supervisor 提供的缺失步骤信息重新规划
     *
     * @param userQuery    原始用户需求
     * @param sopContext   SOP 上下文
     * @param missingSteps Supervisor 反馈的缺失步骤描述列表
     * @return 补全后的步骤列表
     */
    public List<PlanStep> replan(String userQuery, String sopContext, List<String> missingSteps) {
        log.info("[PlannerAgent] 执行补充规划，缺失步骤数: {}", missingSteps.size());

        String missingDesc = String.join("\n", missingSteps.stream()
                .map(s -> "- " + s).toList());

        String response = chatClient.prompt()
                .system(buildPlannerSystemPrompt(sopContext))
                .user("""
                        用户需求：%s
                        
                        Supervisor 审核发现以下步骤缺失，请在原计划基础上补充这些步骤，\
                        返回完整的、包含补充步骤的新计划：
                        
                        缺失的步骤：
                        %s
                        """.formatted(userQuery, missingDesc))
                .call()
                .content();

        return parseSteps(response, userQuery);
    }

    // ======================== 私有方法 ========================

    private String buildPlannerSystemPrompt(String sopContext) {
        return """
                你是一个营销活动配置规划专家。你的任务是将用户的营销配置需求分解为具体、可执行的步骤列表。
                
                【可用的 MCP 工具】
                - createActivity: 创建新的营销活动（需要活动名称、类型、时间、配置）
                - listActivities: 查询活动列表
                - getActivityDetail: 查询单个活动详情
                - updateActivity: 更新已有活动信息
                - toggleActivityStatus: 启用或禁用活动
                - createCoupon: 创建优惠券（属于 COUPON 类活动）
                - configureActivityRules: 配置活动的满减/折扣规则
                
                【SOP 参考流程】
                %s
                
                【输出要求】
                请以 JSON 数组格式输出步骤列表，每个步骤包含以下字段：
                - stepId: 步骤序号（从 1 开始的整数）
                - action: 对应工具名称（必须是可用工具列表中的一个）
                - description: 该步骤的详细执行描述（中文，包含具体参数值）
                - params: 该步骤从用户需求中能确定的参数键值对（JSON 对象）
                - dependsOn: 前置步骤的 stepId 列表（如无则为空数组 []）
                
                【注意】
                - 只返回 JSON 数组，不要有任何额外说明文字
                - 步骤数量控制在 %d 以内
                - 参数值尽量从用户输入中提取，无法确定的用 "待用户确认" 占位
                - 创建活动后才能创建优惠券（dependsOn 中填写 createActivity 步骤的 stepId）
                """.formatted(sopContext, maxSteps);
    }

    private String buildUserPrompt(String userQuery) {
        return "请为以下营销配置需求生成执行步骤计划：\n\n用户需求：" + userQuery;
    }

    private List<PlanStep> parseSteps(String response, String userQuery) {
        if (response == null || response.isBlank()) {
            return buildDefaultStep(userQuery);
        }

        // 提取 JSON 数组部分（LLM 有时会带前缀说明）
        String jsonStr = extractJson(response);
        if (jsonStr == null) {
            log.warn("[PlannerAgent] 无法从响应中提取 JSON，原始响应: {}", response);
            return buildDefaultStep(userQuery);
        }

        try {
            List<PlanStep> steps = objectMapper.readValue(jsonStr, new TypeReference<>() {});
            log.info("[PlannerAgent] 成功解析 {} 个步骤", steps.size());
            return steps;
        } catch (Exception e) {
            log.error("[PlannerAgent] JSON 解析失败: {}", jsonStr, e);
            return buildDefaultStep(userQuery);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 数组
     */
    private String extractJson(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    /**
     * 兜底：生成一个通用的默认步骤（当解析失败时）
     */
    private List<PlanStep> buildDefaultStep(String userQuery) {
        return List.of(new PlanStep(
                1,
                "createActivity",
                "根据用户需求创建营销活动：" + userQuery,
                java.util.Map.of("description", userQuery),
                Collections.emptyList()
        ));
    }
}
