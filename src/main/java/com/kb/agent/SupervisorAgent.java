package com.kb.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Supervisor Agent — 步骤完整性校验
 * <p>
 * 职责：
 * <ol>
 *   <li>接收用户原始意图和 PlannerAgent 生成的步骤列表</li>
 *   <li>验证步骤是否完整、逻辑是否正确、是否缺少必要的前置操作</li>
 *   <li>若发现缺失步骤，返回具体描述，触发 PlannerAgent 重新规划</li>
 * </ol>
 *
 * 上游：{@link ConfigAgentOrchestrator}
 * 下游：若验证通过，进入 {@link SkillAgent} 执行
 */
@Slf4j
@Service
public class SupervisorAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SupervisorAgent(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 验证步骤列表是否完整
     *
     * @param originalQuery 用户的原始需求
     * @param steps         PlannerAgent 生成的步骤列表
     * @return 校验结果（通过/不通过 + 缺失步骤描述）
     */
    public ValidationResult validate(String originalQuery, List<PlanStep> steps) {
        log.info("[SupervisorAgent] 开始校验步骤完整性，步骤数: {}", steps.size());

        if (steps.isEmpty()) {
            log.warn("[SupervisorAgent] 步骤列表为空，要求重新规划");
            return ValidationResult.fail(
                    List.of("缺少所有执行步骤，需要重新规划"),
                    "步骤列表为空"
            );
        }

        String stepsJson = stepsToJson(steps);

        String response;
        try {
            response = chatClient.prompt()
                    .system(buildSupervisorSystemPrompt())
                    .user(buildUserPrompt(originalQuery, stepsJson))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[SupervisorAgent] LLM 调用失败，默认放行", e);
            return ValidationResult.pass(); // LLM 异常时不阻塞主流程
        }

        log.debug("[SupervisorAgent] LLM 校验响应: {}", response);
        return parseValidationResult(response);
    }

    // ======================== 私有方法 ========================

    private String buildSupervisorSystemPrompt() {
        return """
                你是一个营销活动配置流程的质量审核专家。你的任务是验证给定的执行步骤是否完整和正确。
                
                【审核标准】
                1. 步骤完整性：是否包含完成用户需求所需的所有关键步骤
                2. 依赖关系：前置步骤是否都已包含（如创建优惠券前必须先创建活动）
                3. 步骤逻辑：步骤顺序是否合理，是否有矛盾
                4. 参数完整性：关键步骤是否有必要的参数
                
                【常见缺失步骤案例】
                - 创建 COUPON 类活动后，应有创建优惠券（createCoupon）步骤
                - 配置满减规则前，应先有创建活动（createActivity）步骤
                - 上线活动前，应先完成活动创建和规则配置
                
                【输出格式】
                请严格以以下 JSON 格式返回，不要有任何额外说明：
                {"complete": true/false, "missingSteps": ["缺失步骤1描述", "缺失步骤2描述"], "comment": "审核说明"}
                
                - complete=true 时，missingSteps 必须为空数组 []
                - complete=false 时，missingSteps 必须包含至少一个缺失步骤的具体描述
                - comment 为简短的审核说明（20字以内）
                """;
    }

    private String buildUserPrompt(String originalQuery, String stepsJson) {
        return """
                用户需求：%s
                
                待审核的执行步骤：
                %s
                
                请审核上述步骤是否足以完成用户需求，并按要求格式返回审核结果。
                """.formatted(originalQuery, stepsJson);
    }

    private String stepsToJson(List<PlanStep> steps) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(steps);
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            for (PlanStep step : steps) {
                sb.append("步骤 ").append(step.stepId()).append(": ")
                  .append(step.description()).append("\n");
            }
            return sb.toString();
        }
    }

    private ValidationResult parseValidationResult(String response) {
        if (response == null || response.isBlank()) {
            log.warn("[SupervisorAgent] 响应为空，默认通过");
            return ValidationResult.pass();
        }

        // 提取 JSON 对象
        String jsonStr = extractJson(response);
        if (jsonStr == null) {
            log.warn("[SupervisorAgent] 无法从响应中提取 JSON，默认通过");
            return ValidationResult.pass();
        }

        try {
            SupervisorResponse resp = objectMapper.readValue(jsonStr, SupervisorResponse.class);
            if (resp.complete()) {
                log.info("[SupervisorAgent] 步骤校验通过: {}", resp.comment());
                return ValidationResult.pass();
            } else {
                List<String> missing = resp.missingSteps() != null ? resp.missingSteps() : Collections.emptyList();
                log.info("[SupervisorAgent] 步骤不完整，缺失 {} 个步骤: {}", missing.size(), missing);
                return ValidationResult.fail(missing, resp.comment());
            }
        } catch (Exception e) {
            log.error("[SupervisorAgent] JSON 解析失败: {}", jsonStr, e);
            return ValidationResult.pass(); // 解析失败时默认放行
        }
    }

    private String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    // ======================== 内部 DTO ==========

    private record SupervisorResponse(
            @JsonProperty("complete") boolean complete,
            @JsonProperty("missingSteps") List<String> missingSteps,
            @JsonProperty("comment") String comment
    ) {}
}
