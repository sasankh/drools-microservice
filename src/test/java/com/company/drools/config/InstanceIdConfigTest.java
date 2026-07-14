package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstanceIdConfigTest {

  @Test
  @DisplayName("droolsInstanceId returns a non-blank UUID string")
  void droolsInstanceIdReturnsUuid() {
    String id = new InstanceIdConfig().droolsInstanceId();

    assertThat(id).isNotBlank();
    // Throws if not a valid UUID format — fail-fast guarantee for pub/sub dedup
    UUID.fromString(id);
  }

  @Test
  @DisplayName("each call produces a distinct UUID — siblings won't collide")
  void distinctIds() {
    InstanceIdConfig cfg = new InstanceIdConfig();
    String a = cfg.droolsInstanceId();
    String b = cfg.droolsInstanceId();

    assertThat(a).isNotEqualTo(b);
  }
}
