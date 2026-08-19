package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.drools.cache.RefreshEvent;
import com.company.drools.cache.RuleRefreshPublisher;
import com.company.drools.cache.RuleRefreshSubscriber;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.core.model.RuleMetadata.RuleStatus;
import com.company.drools.storage.RuleStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end pub/sub integration test for cross-task refresh convergence. Simulates two ECS tasks
 * (instance A and instance B) sharing one Redis. When instance A publishes a refresh event,
 * instance B should receive it, skip self-emitted events, and trigger its own engine refresh.
 *
 * <p>This is the cornerstone of the multi-instance compiled-state convergence story: it proves that
 * the pub/sub wire format and self-dedup actually work over a real Redis connection, not just in
 * mocked unit tests.
 */
@DisplayName("Redis Pub/Sub Integration Tests")
@Testcontainers
class RedisPubSubIntegrationTest {

  private static final String CHANNEL = "drools:rule:events";
  private static final String INSTANCE_A = "instance-A";
  private static final String INSTANCE_B = "instance-B";

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate stringRedisTemplate;
  private static ObjectMapper objectMapper;

  // Instance A (publisher side)
  private RuleRefreshPublisher publisherA;

  // Instance B (subscriber side) — represents a sibling ECS task receiving events
  private RuleStorage storageB;
  private DroolsEngineService engineB;
  private RuleRefreshSubscriber subscriberB;
  private RedisMessageListenerContainer listenerContainerB;
  private SimpleMeterRegistry meterRegistryB;

  // Instance A's own subscriber — needed to prove self-dedup works
  private RuleRefreshSubscriber subscriberA;
  private RuleStorage storageA;
  private DroolsEngineService engineA;
  private RedisMessageListenerContainer listenerContainerA;
  private SimpleMeterRegistry meterRegistryA;

  @BeforeAll
  static void setupRedis() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @AfterAll
  static void tearDownRedis() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @BeforeEach
  void setUp() {
    // Each test starts fresh: clear Redis, wire up two parallel instances sharing the same Redis.
    stringRedisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();

    // Instance A — publisher + its own subscriber (to verify self-dedup)
    CircuitBreaker cbA = CircuitBreaker.of("a", CircuitBreakerConfig.ofDefaults());
    meterRegistryA = new SimpleMeterRegistry();
    publisherA =
        new RuleRefreshPublisher(
            stringRedisTemplate, cbA, INSTANCE_A, CHANNEL, meterRegistryA, objectMapper);

    storageA = Mockito.mock(RuleStorage.class);
    engineA = Mockito.mock(DroolsEngineService.class);
    subscriberA =
        new RuleRefreshSubscriber(storageA, engineA, INSTANCE_A, objectMapper, meterRegistryA);
    listenerContainerA = newListenerContainer(subscriberA);

    // Instance B — subscriber only (the "sibling task")
    meterRegistryB = new SimpleMeterRegistry();
    storageB = Mockito.mock(RuleStorage.class);
    engineB = Mockito.mock(DroolsEngineService.class);
    subscriberB =
        new RuleRefreshSubscriber(storageB, engineB, INSTANCE_B, objectMapper, meterRegistryB);
    listenerContainerB = newListenerContainer(subscriberB);

    // Wait briefly for the subscribers to actually attach to the channel
    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> listenerContainerA.isRunning() && listenerContainerB.isRunning());
  }

  private RedisMessageListenerContainer newListenerContainer(RuleRefreshSubscriber subscriber) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(subscriber, new PatternTopic(CHANNEL));
    container.afterPropertiesSet();
    container.start();
    return container;
  }

  private Rule rule(String id) {
    return new Rule(
        id,
        "rule \"" + id + "\" when then end",
        new RuleMetadata(
            "1", LocalDateTime.now(), LocalDateTime.now(), RuleStatus.ACTIVE, null, 0L, 0.0));
  }

  // ─── Single-rule fan-out ────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Single-rule refresh: A publishes → B receives and refreshes its engine, A skips self")
  void singleRuleRefreshFansOutToSiblings() {
    Rule expected = rule("pricing.simple");
    when(storageB.getRule("pricing.simple")).thenReturn(Optional.of(expected));

    publisherA.publishRefresh("pricing.simple");

    // Instance B's subscriber should receive the event, fetch the rule, refresh its engine
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              verify(storageB, atLeastOnce()).getRule("pricing.simple");
              verify(engineB).loadOrReplaceRule(expected);
            });

    // Verify B's metrics show the event was received
    assertThat(meterRegistryB.counter("drools.refresh.received", "event", "RULE_REFRESHED").count())
        .isEqualTo(1.0);

    // Instance A receives its own event too — but skips it via instanceId dedup
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> meterRegistryA.counter("drools.refresh.skipped_self").count() >= 1.0);

    verify(engineA, Mockito.never()).loadOrReplaceRule(Mockito.any());
  }

  // ─── Bulk fan-out ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("Bulk refresh: A publishes BULK → B refreshes its full rule set, A skips self")
  void bulkRefreshFansOut() {
    List<Rule> all = List.of(rule("a"), rule("b"), rule("c"));
    when(storageB.getAllRules()).thenReturn(all);

    publisherA.publishBulkRefresh();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              verify(storageB, atLeastOnce()).getAllRules();
              verify(engineB).loadRules(all);
            });

    assertThat(
            meterRegistryB
                .counter("drools.refresh.received", "event", "RULE_REFRESHED_BULK")
                .count())
        .isEqualTo(1.0);

    // A skips its own bulk event
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> meterRegistryA.counter("drools.refresh.skipped_self").count() >= 1.0);
    verify(engineA, Mockito.never()).loadRules(Mockito.any());
  }

  // ─── Delete event ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("Delete event: B records receipt, engine NOT auto-called (deferred to v2)")
  void deleteEventReachesSibling() {
    publisherA.publishDelete("retired.rule");

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                meterRegistryB.counter("drools.refresh.received", "event", "RULE_DELETED").count()
                    >= 1.0);

    verify(engineB, Mockito.never()).loadRules(Mockito.any());
    verify(engineB, Mockito.never()).loadOrReplaceRule(Mockito.any());
  }

  // ─── Wire-format compatibility ─────────────────────────────────────────────

  @Test
  @DisplayName("Pub/sub JSON wire format: A's publish produces a payload B can deserialise")
  void wireFormatCompat() throws Exception {
    publisherA.publishRefresh("pricing.simple");

    // Capture B's "received" before any handling logic, by inspecting Redis channel directly is
    // tricky — but we already proved end-to-end deserialise via the engineB.loadOrReplaceRule
    // path in singleRuleRefreshFansOutToSiblings. Here we just make sure both directions
    // (publisher emit + subscriber decode) work over real Redis without throwing.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              double received =
                  meterRegistryB
                      .counter("drools.refresh.received", "event", "RULE_REFRESHED")
                      .count();
              assertThat(received).isPositive();
            });

    // Spot-check the schema directly via the publisher: ensure round-trip yields the expected
    // EventType
    RefreshEvent decoded =
        objectMapper.readValue(
            objectMapper.writeValueAsBytes(RefreshEvent.refreshed("x", "y")), RefreshEvent.class);
    assertThat(decoded.event()).isEqualTo(RefreshEvent.EventType.RULE_REFRESHED);
  }

  // ─── Cleanup ────────────────────────────────────────────────────────────────

  @AfterEach
  void tearDownContainers() {
    if (listenerContainerA != null && listenerContainerA.isRunning()) {
      listenerContainerA.stop();
    }
    if (listenerContainerB != null && listenerContainerB.isRunning()) {
      listenerContainerB.stop();
    }
  }
}
