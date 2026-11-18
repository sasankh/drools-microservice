package com.company.drools.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RefreshEvent} JSON serialization. */
class RefreshEventTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Test
  @DisplayName("RULE_REFRESHED roundtrip preserves all fields and snake_case JSON keys")
  void refreshedRoundtrip() throws Exception {
    Instant ts = Instant.parse("2026-05-11T10:30:45.123Z");
    RefreshEvent original =
        new RefreshEvent(
            RefreshEvent.EventType.RULE_REFRESHED, "pricing.simple", "instance-abc", ts);

    String json = objectMapper.writeValueAsString(original);
    assertThat(json)
        .contains("\"event\":\"RULE_REFRESHED\"")
        .contains("\"rule_id\":\"pricing.simple\"")
        .contains("\"source_instance_id\":\"instance-abc\"");

    RefreshEvent decoded = objectMapper.readValue(json, RefreshEvent.class);
    assertThat(decoded).isEqualTo(original);
  }

  @Test
  @DisplayName("RULE_REFRESHED_BULK with null ruleId roundtrips cleanly")
  void bulkRoundtripWithNullRuleId() throws Exception {
    RefreshEvent original = RefreshEvent.bulkRefreshed("instance-bulk");

    String json = objectMapper.writeValueAsString(original);
    RefreshEvent decoded = objectMapper.readValue(json, RefreshEvent.class);

    assertThat(decoded.event()).isEqualTo(RefreshEvent.EventType.RULE_REFRESHED_BULK);
    assertThat(decoded.ruleId()).isNull();
    assertThat(decoded.sourceInstanceId()).isEqualTo("instance-bulk");
  }

  @Test
  @DisplayName("Unknown event type fails deserialization (deliberate — subscriber handles)")
  void unknownEventTypeFailsSafely() {
    String unknown =
        "{\"event\":\"NEW_TYPE_FROM_FUTURE\",\"rule_id\":\"x\",\"source_instance_id\":\"y\","
            + "\"timestamp\":\"2026-05-11T10:30:45.123Z\"}";

    // Jackson throws on unknown enum constant by default. Subscriber catches deserialize errors
    // and logs ERROR — see RuleRefreshSubscriberTest.malformedJson coverage.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> objectMapper.readValue(unknown, RefreshEvent.class))
        .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
  }
}
