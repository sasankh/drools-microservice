package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@DisplayName("ThreadPoolConfig")
class ThreadPoolConfigTest {

  private ThreadPoolConfig config;

  @BeforeEach
  void setUp() throws Exception {
    config = new ThreadPoolConfig();
    setField(config, "ruleExecutionCorePoolSize", 4);
    setField(config, "ruleExecutionMaxPoolSize", 10);
    setField(config, "ruleExecutionQueueCapacity", 20);
    setField(config, "ruleExecutionKeepAliveSeconds", 30);
    setField(config, "storageCorePoolSize", 2);
    setField(config, "storageMaxPoolSize", 8);
    setField(config, "storageQueueCapacity", 10);
    setField(config, "storageKeepAliveSeconds", 30);
  }

  @Test
  @DisplayName("ruleExecutionExecutor creates configured thread pool")
  void testRuleExecutionExecutor() {
    Executor executor = config.ruleExecutionExecutor();

    assertThat(executor).isNotNull();
    assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);

    ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
    assertThat(taskExecutor.getCorePoolSize()).isEqualTo(4);
    assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(10);
    assertThat(taskExecutor.getThreadNamePrefix()).isEqualTo("rule-exec-");
  }

  @Test
  @DisplayName("storageExecutor creates configured thread pool")
  void testStorageExecutor() {
    Executor executor = config.storageExecutor();

    assertThat(executor).isNotNull();
    assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);

    ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
    assertThat(taskExecutor.getCorePoolSize()).isEqualTo(2);
    assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(8);
    assertThat(taskExecutor.getThreadNamePrefix()).isEqualTo("storage-");
  }

  @Test
  @DisplayName("getRuleExecutionPoolStats returns pool statistics")
  void testGetRuleExecutionPoolStats() {
    // Initialize the executor first
    config.ruleExecutionExecutor();

    String stats = config.getRuleExecutionPoolStats();
    assertThat(stats).contains("RuleExecutionPool[");
    assertThat(stats).contains("active=");
    assertThat(stats).contains("pool=");
  }

  @Test
  @DisplayName("getStoragePoolStats returns pool statistics")
  void testGetStoragePoolStats() {
    // Initialize the executor first
    config.storageExecutor();

    String stats = config.getStoragePoolStats();
    assertThat(stats).contains("StoragePool[");
    assertThat(stats).contains("active=");
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
