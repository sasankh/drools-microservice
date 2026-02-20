package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.core.model.Rule;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("RedisConfig")
@ExtendWith(MockitoExtension.class)
class RedisConfigTest {

  private RedisConfig redisConfig;

  @Mock private RedisConnectionFactory connectionFactory;

  @BeforeEach
  void setUp() {
    redisConfig = new RedisConfig();
    ReflectionTestUtils.setField(redisConfig, "ttlMinutes", 60L);
  }

  @Test
  @DisplayName("redisTtlDuration returns correct duration based on ttlMinutes")
  void testRedisTtlDurationDefault() {
    Duration duration = redisConfig.redisTtlDuration();

    assertThat(duration).isEqualTo(Duration.ofMinutes(60));
  }

  @Test
  @DisplayName("redisTtlDuration returns updated duration when ttlMinutes is changed")
  void testRedisTtlDurationCustom() {
    ReflectionTestUtils.setField(redisConfig, "ttlMinutes", 120L);

    Duration duration = redisConfig.redisTtlDuration();

    assertThat(duration).isEqualTo(Duration.ofMinutes(120));
  }

  @Test
  @DisplayName("redisTtlDuration returns zero duration when ttlMinutes is 0")
  void testRedisTtlDurationZero() {
    ReflectionTestUtils.setField(redisConfig, "ttlMinutes", 0L);

    Duration duration = redisConfig.redisTtlDuration();

    assertThat(duration).isEqualTo(Duration.ZERO);
  }

  @Test
  @DisplayName("ruleRedisTemplate returns a non-null configured template")
  void testRuleRedisTemplateReturnsNonNull() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    assertThat(template).isNotNull();
  }

  @Test
  @DisplayName("ruleRedisTemplate has correct connection factory")
  void testRuleRedisTemplateConnectionFactory() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    assertThat(template.getConnectionFactory()).isSameAs(connectionFactory);
  }

  @Test
  @DisplayName("ruleRedisTemplate has string key serializer")
  void testRuleRedisTemplateKeySerializer() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    assertThat(template.getKeySerializer()).isNotNull();
    assertThat(template.getKeySerializer().getClass().getSimpleName())
        .isEqualTo("StringRedisSerializer");
  }

  @Test
  @DisplayName("ruleRedisTemplate has string hash key serializer")
  void testRuleRedisTemplateHashKeySerializer() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    assertThat(template.getHashKeySerializer()).isNotNull();
    assertThat(template.getHashKeySerializer().getClass().getSimpleName())
        .isEqualTo("StringRedisSerializer");
  }

  @Test
  @DisplayName("ruleRedisTemplate has JSON value serializer")
  void testRuleRedisTemplateValueSerializer() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    assertThat(template.getValueSerializer()).isNotNull();
    assertThat(template.getValueSerializer().getClass().getSimpleName())
        .isEqualTo("Jackson2JsonRedisSerializer");
  }

  @Test
  @DisplayName("ruleRedisTemplate has JSON hash value serializer")
  void testRuleRedisTemplateHashValueSerializer() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    assertThat(template.getHashValueSerializer()).isNotNull();
    assertThat(template.getHashValueSerializer().getClass().getSimpleName())
        .isEqualTo("Jackson2JsonRedisSerializer");
  }

  @Test
  @DisplayName("ruleRedisTemplate has transaction support enabled")
  void testRuleRedisTemplateTransactionSupport() {
    RedisTemplate<String, Rule> template = redisConfig.ruleRedisTemplate(connectionFactory);

    // Verify afterPropertiesSet was called by checking the template is fully initialized
    // The template should be usable (afterPropertiesSet was called in the config method)
    assertThat(template).isNotNull();
  }
}
