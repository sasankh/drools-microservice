package com.company.drools.config;

import com.company.drools.cache.RuleRefreshSubscriber;
import com.company.drools.core.model.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Redis configuration for distributed rule caching. Only activated when redis.enabled=true. */
@Configuration
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
public class RedisConfig {

  private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

  @Value("${redis.ttl-minutes:60}")
  private long ttlMinutes;

  /** Configures RedisTemplate for Rule objects with JSON serialization. */
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

  /** Creates a Jackson2JsonRedisSerializer for Rule objects. */
  private Jackson2JsonRedisSerializer<Rule> createRuleJsonSerializer() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    PolymorphicTypeValidator ptv =
        BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType("com.company.drools.core.model")
            .allowIfBaseType("java.util")
            .allowIfBaseType("java.time")
            .build();
    objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);

    return new Jackson2JsonRedisSerializer<>(objectMapper, Rule.class);
  }

  /** Provides the configured TTL duration for cache entries. */
  @Bean
  public Duration redisTtlDuration() {
    return Duration.ofMinutes(ttlMinutes);
  }

  /**
   * Subscribes {@link RuleRefreshSubscriber} to the pub/sub channel so this task receives refresh
   * events from sibling ECS tasks. Active only when {@code redis.pubsub.enabled=true} (default on
   * when Redis is on). Container auto-reconnects on Redis connection drops.
   */
  @Bean
  @ConditionalOnProperty(name = "redis.pubsub.enabled", havingValue = "true", matchIfMissing = true)
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RuleRefreshSubscriber subscriber,
      @Value("${redis.pubsub.channel:drools:rule:events}") String channel) {
    log.info("Configuring Redis pub/sub listener on channel: {}", channel);
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(subscriber, new PatternTopic(channel));
    return container;
  }
}
