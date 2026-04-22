package com.kb.agent;

import java.util.List;
import java.util.Map;

/**
 * 规划步骤模型
 * <p>
 * 由 PlannerAgent 生成，描述执行一个操作所需的单个步骤。
 * SkillAgent 根据此模型决定调用哪个 MCP 工具。
 */
public record PlanStep(

        /**
         * 步骤序号（从 1 开始）
         */
        int stepId,

        /**
         * 对应的 MCP 工具名称，如：createActivity、createCoupon
         * 与 MarketingMcpTools 中的方法名对应
         */
        String action,

        /**
         * 步骤的自然语言描述，如：
         * "创建名为'双十一满减活动'的营销活动，活动类型为 COUPON，时间为 2024-11-11"
         */
        String description,

        /**
         * 该步骤所需的参数键值对（由 PlannerAgent 从用户 Query 中提取）
         */
        Map<String, Object> params,

        /**
         * 前置步骤的 stepId 列表（空表示可直接执行）
         */
        List<Integer> dependsOn
) {
    /**
     * 判断此步骤是否有前置依赖
     */
    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("PlanStep{stepId=%d, action='%s', description='%s', dependsOn=%s}",
                stepId, action, description, dependsOn);
    }
}
