package com.company.drools.config;

import com.company.drools.cache.RuleRefreshSubscriber;
import com.company.drools.core.model.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.concurrent.ThreadPoolExecutor;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.FixedBackOff;

/** Redis configuration for distributed rule caching. Only activated when redis.enabled=true. */
@Configuration
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
public class RedisConfig {

  private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

  @Value("${redis.drl-rules.ttl-minutes:15}")
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
    // Rule is a flat, fixed-shape POJO (ruleId/content/metadata — all concrete types: String,
    // LocalDateTime, enum, primitives), and the serializer already pins the root type to
    // Rule.class.
    // Jackson default typing is therefore unnecessary here and only widens the deserialization
    // gadget surface, so it is intentionally NOT enabled. (P6 / finding #28 defense-in-depth)
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    return new Jackson2JsonRedisSerializer<>(objectMapper, Rule.class);
  }

  /**
   * Subscribes {@link RuleRefreshSubscriber} to the pub/sub channel so this task receives refresh
   * events from sibling ECS tasks. Active only when {@code redis.pubsub.enabled=true} (default on
   * when Redis is on).
   *
   * <p>An explicit {@link FixedBackOff} recovery policy is configured so the dedicated pub/sub
   * Lettuce connection deterministically re-subscribes within ≤5s after Redis becomes reachable
   * following a restart. Fixed interval (over ExponentialBackOff) is intentional: it bounds the
   * worst-case re-subscribe latency, which matters for the cross-task convergence guarantee — an
   * exponential window could happen to be mid-wait when Redis comes back.
   */
  @Bean
  @ConditionalOnProperty(name = "redis.pubsub.enabled", havingValue = "true", matchIfMissing = true)
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RuleRefreshSubscriber subscriber,
      ThreadPoolTaskExecutor ruleRefreshListenerExecutor,
      @Value("${redis.pubsub.channel:drools:rule:events}") String channel) {
    log.info("Configuring Redis pub/sub listener on channel: {}", channel);
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(subscriber, new PatternTopic(channel));
    // Run message handling — which triggers a rule recompile — on a dedicated, bounded, single
    // thread rather than the Lettuce subscription/dispatch thread. A slow recompile no longer
    // blocks
    // delivery of subsequent refresh events, and the bounded queue caps pile-up under bursts. (S6)
    container.setTaskExecutor(ruleRefreshListenerExecutor);
    // 2s interval bounds worst-case re-subscribe latency to ≤2s after Redis is reachable
    // (matching the Phase 9.4 convergence-recovery deadline). 60 attempts/min per listener
    // is well within what a healthy or recovering Redis can absorb (one TCP connect each).
    container.setRecoveryBackoff(new FixedBackOff(2_000L, Long.MAX_VALUE));
    log.info("Redis pub/sub recovery backoff: fixed 2s interval, infinite attempts");
    return container;
  }

  /**
   * Dedicated single-thread executor for pub/sub message handling (rule recompiles). Bounded queue
   * + caller-runs so nothing is silently dropped, but the (possibly long) recompile never runs on
   * the Lettuce dispatch thread. Spring manages init/shutdown. (S6)
   */
  @Bean
  @ConditionalOnProperty(name = "redis.pubsub.enabled", havingValue = "true", matchIfMissing = true)
  public ThreadPoolTaskExecutor ruleRefreshListenerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("rule-refresh-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return executor;
  }
}
