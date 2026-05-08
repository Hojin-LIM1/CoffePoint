package com.coffeepoint.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate 사용 (= RedisTemplate<String, String>)
     * - Object 타입 대비 serializer 이슈 없음
     * - ZSET value에 menuId만 저장하므로 String이면 충분
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
