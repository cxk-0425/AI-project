package com.kb.agent;

import java.util.List;

/**
 * Supervisor Agent 校验结果模型
 */
public record ValidationResult(

        /**
         * 步骤是否完整
         */
        boolean complete,

        /**
         * 缺失的步骤描述列表（complete=true 时为空列表）
         */
        List<String> missingSteps,

        /**
         * 校验说明（如"缺少优惠券创建步骤"）
         */
        String comment
) {
    /**
     * 快捷创建"通过"结果
     */
    public static ValidationResult pass() {
        return new ValidationResult(true, List.of(), "步骤完整，可以执行");
    }

    /**
     * 快捷创建"不通过"结果
     */
    public static ValidationResult fail(List<String> missingSteps, String comment) {
        return new ValidationResult(false, missingSteps, comment);
    }
}
