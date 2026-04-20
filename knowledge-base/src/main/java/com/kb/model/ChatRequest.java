package com.kb.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 问答请求 DTO
 */
@Data
public class ChatRequest {

    /** 用户问题 */
    @NotBlank(message = "问题不能为空")
    private String question;

    /** 对话历史 ID（可选，用于多轮对话） */
    private String sessionId;

    /** 是否流式返回（默认 true） */
    private boolean stream = true;
}
