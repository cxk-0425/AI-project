package com.kb.config;

import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型配置
 * <p>
 * Spring AI OpenAI Starter 已自动装配 EmbeddingModel（OpenAiEmbeddingModel），
 * 无需手动注册 Bean。所有参数通过 application.yml 配置：
 * <pre>
 *   spring.ai.openai.embedding.options.model = text-embedding-3-small
 * </pre>
 *
 * 若需切换为其他 Embedding 提供商（如 Ollama / Azure OpenAI），
 * 在此处替换对应的 Starter 依赖并修改 application.yml 即可。
 */
@Configuration
public class EmbeddingConfig {
    // auto-configured by spring-ai-openai-spring-boot-starter
    // EmbeddingModel is available via @Autowired
}
