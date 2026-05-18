package com.platform.ingestion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configures a String-keyed RedisTemplate used for event deduplication.
 * Connection settings come from spring.data.redis.* in application.yml.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer ser = new StringRedisSerializer();
        template.setKeySerializer(ser);
        template.setValueSerializer(ser);
        template.setHashKeySerializer(ser);
        template.setHashValueSerializer(ser);
        template.afterPropertiesSet();

        return template;
    }
}
