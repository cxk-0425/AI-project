package com.kb.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kb.token.TokenInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置
 * <p>
 * 为 TokenInfo 提供专用的 RedisTemplate，使用 Jackson2 序列化为 JSON 存储。
 * <p>
 * 为何不用默认 RedisTemplate：
 * <ul>
 *   <li>默认使用 JdkSerializationRedisSerializer（二进制，不可读，版本敏感）</li>
 *   <li>TokenInfo 是 Java Record，需要 Jackson 处理 Instant 类型</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * TokenInfo 专用 RedisTemplate（Key: String，Value: TokenInfo JSON）
     */
    @Bean
    public RedisTemplate<String, TokenInfo> tokenRedisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, TokenInfo> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 序列化：String
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 序列化：Jackson2 JSON（支持 Java 8 时间类型）
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 存储类型信息，反序列化时还原正确类型
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        Jackson2JsonRedisSerializer<TokenInfo> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, TokenInfo.class);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
