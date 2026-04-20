package com.kb.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置
 * <p>
 * Spring AI 1.0 通过 {@link ChatClient.Builder} 构建 ChatClient，
 * 支持流式（stream）和同步（call）两种调用方式。
 */
@Configuration
public class ChatModelConfig {

    /**
     * 注册全局 ChatClient Bean，后续在 Service 层注入使用。
     * ChatModel 由 spring-ai-openai-spring-boot-starter 自动装配。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
