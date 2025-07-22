package com.company.drools.config;

import com.company.drools.core.model.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis configuration for distributed rule caching.
 * Only activated when redis.enabled=true.
 */
@Configuration
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
public class RedisConfig {

  private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

  @Value("${redis.ttl-minutes:60}")
  private long ttlMinutes;

  /**
   * Configures RedisTemplate for Rule objects with JSON serialization.
   */
  @Bean
  public RedisTemplate<String, Rule> ruleRedisTemplate(RedisConnectionFactory connectionFactory) {
    log.info("Configuring Redis template for Rule caching with TTL: {} minutes", ttlMinutes);

    RedisTemplate<String, Rule> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    // Configure serializers
    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<Rule> jsonSerializer = createRuleJsonSerializer();

    // Set key and value serializers
    template.setKeySerializer(stringSerializer);
    template.setHashKeySerializer(stringSerializer);
    template.setValueSerializer(jsonSerializer);
    template.setHashValueSerializer(jsonSerializer);

    // Enable transaction support
    template.setEnableTransactionSupport(true);

    template.afterPropertiesSet();
    log.info("Redis template configured successfully");
    return template;
  }

  /**
   * Creates a Jackson2JsonRedisSerializer for Rule objects.
   */
  private Jackson2JsonRedisSerializer<Rule> createRuleJsonSerializer() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    
    Jackson2JsonRedisSerializer<Rule> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, Rule.class);
    return serializer;
  }

  /**
   * Provides the configured TTL duration for cache entries.
   */
  @Bean
  public Duration redisTtlDuration() {
    return Duration.ofMinutes(ttlMinutes);
  }

  /**
   * General purpose RedisTemplate for String operations.
   */
  @Bean
  public RedisTemplate<String, String> stringRedisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    
    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    template.setKeySerializer(stringSerializer);
    template.setValueSerializer(stringSerializer);
    template.setHashKeySerializer(stringSerializer);
    template.setHashValueSerializer(stringSerializer);
    
    template.setEnableTransactionSupport(true);
    template.afterPropertiesSet();
    return template;
  }
}