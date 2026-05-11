package com.company.drools.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RuleMetadata")
class RuleMetadataTest {

  @Nested
  @DisplayName("Constructor")
  class Constructor {

    @Test
    @DisplayName("creates metadata with all valid parameters")
    void testValidConstruction() {
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime modified = now.minusHours(1);

      RuleMetadata metadata =
          new RuleMetadata("2.0", now, modified, RuleMetadata.RuleStatus.ACTIVE, null, 10, 5.5);

      assertThat(metadata.getVersion()).isEqualTo("2.0");
      assertThat(metadata.getLoadedAt()).isEqualTo(now);
      assertThat(metadata.getLastModified()).isEqualTo(modified);
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(metadata.getErrorMessage()).isNull();
      assertThat(metadata.getExecutionCount()).isEqualTo(10);
      assertThat(metadata.getAverageExecutionTimeMs()).isEqualTo(5.5);
    }

    @Test
    @DisplayName("throws NullPointerException when loadedAt is null")
    void testNullLoadedAt() {
      assertThatThrownBy(
              () ->
                  new RuleMetadata("1.0", null, null, RuleMetadata.RuleStatus.ACTIVE, null, 0, 0.0))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("Loaded at cannot be null");
    }

    @Test
    @DisplayName("throws NullPointerException when status is null")
    void testNullStatus() {
      LocalDateTime now = LocalDateTime.now();
      assertThatThrownBy(() -> new RuleMetadata("1.0", now, null, null, null, 0, 0.0))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("Rule status cannot be null");
    }

    @Test
    @DisplayName("allows null lastModified")
    void testNullLastModified() {
      RuleMetadata metadata =
          new RuleMetadata(
              "1.0", LocalDateTime.now(), null, RuleMetadata.RuleStatus.LOADING, null, 0, 0.0);

      assertThat(metadata.getLastModified()).isNull();
    }

    @Test
    @DisplayName("allows null errorMessage")
    void testNullErrorMessage() {
      RuleMetadata metadata =
          new RuleMetadata(
              "1.0", LocalDateTime.now(), null, RuleMetadata.RuleStatus.ACTIVE, null, 0, 0.0);

      assertThat(metadata.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("stores errorMessage when provided")
    void testWithErrorMessage() {
      RuleMetadata metadata =
          new RuleMetadata(
              "1.0",
              LocalDateTime.now(),
              null,
              RuleMetadata.RuleStatus.ERROR,
              "compilation failed",
              0,
              0.0);

      assertThat(metadata.getErrorMessage()).isEqualTo("compilation failed");
    }
  }

  @Nested
  @DisplayName("createNew factory method")
  class CreateNew {

    @Test
    @DisplayName("creates metadata with default values")
    void testCreateNewDefaults() {
      RuleMetadata metadata = RuleMetadata.createNew();

      assertThat(metadata.getVersion()).isEqualTo("1.0");
      assertThat(metadata.getLoadedAt()).isNotNull();
      assertThat(metadata.getLastModified()).isNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.LOADING);
      assertThat(metadata.getErrorMessage()).isNull();
      assertThat(metadata.getExecutionCount()).isZero();
      assertThat(metadata.getAverageExecutionTimeMs()).isZero();
    }
  }

  @Nested
  @DisplayName("withStatus")
  class WithStatus {

    @Test
    @DisplayName("transitions to ACTIVE status")
    void testWithStatusActive() {
      RuleMetadata original = RuleMetadata.createNew();
      RuleMetadata updated = original.withStatus(RuleMetadata.RuleStatus.ACTIVE);

      assertThat(updated.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(updated.getVersion()).isEqualTo(original.getVersion());
      assertThat(updated.getLoadedAt()).isEqualTo(original.getLoadedAt());
      assertThat(updated.getExecutionCount()).isEqualTo(original.getExecutionCount());
    }

    @Test
    @DisplayName("transitions to DISABLED status")
    void testWithStatusDisabled() {
      RuleMetadata metadata = RuleMetadata.createNew().withStatus(RuleMetadata.RuleStatus.DISABLED);

      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.DISABLED);
    }

    @Test
    @DisplayName("transitions to ERROR status")
    void testWithStatusError() {
      RuleMetadata metadata = RuleMetadata.createNew().withStatus(RuleMetadata.RuleStatus.ERROR);

      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
    }

    @Test
    @DisplayName("preserves all other fields when changing status")
    void testWithStatusPreservesFields() {
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime modified = now.minusDays(1);
      RuleMetadata original =
          new RuleMetadata("2.0", now, modified, RuleMetadata.RuleStatus.LOADING, "msg", 5, 3.2);

      RuleMetadata updated = original.withStatus(RuleMetadata.RuleStatus.ACTIVE);

      assertThat(updated.getVersion()).isEqualTo("2.0");
      assertThat(updated.getLoadedAt()).isEqualTo(now);
      assertThat(updated.getLastModified()).isEqualTo(modified);
      assertThat(updated.getErrorMessage()).isEqualTo("msg");
      assertThat(updated.getExecutionCount()).isEqualTo(5);
      assertThat(updated.getAverageExecutionTimeMs()).isEqualTo(3.2);
    }
  }

  @Nested
  @DisplayName("withError")
  class WithError {

    @Test
    @DisplayName("sets status to ERROR with message")
    void testWithErrorSetsStatusAndMessage() {
      RuleMetadata original = RuleMetadata.createNew().withStatus(RuleMetadata.RuleStatus.ACTIVE);

      RuleMetadata errored = original.withError("Compilation failed: syntax error");

      assertThat(errored.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(errored.getErrorMessage()).isEqualTo("Compilation failed: syntax error");
    }

    @Test
    @DisplayName("sets status to ERROR with null message")
    void testWithErrorNullMessage() {
      RuleMetadata errored = RuleMetadata.createNew().withError(null);

      assertThat(errored.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(errored.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("sets status to ERROR with empty message")
    void testWithErrorEmptyMessage() {
      RuleMetadata errored = RuleMetadata.createNew().withError("");

      assertThat(errored.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(errored.getErrorMessage()).isEmpty();
    }

    @Test
    @DisplayName("preserves other fields when setting error")
    void testWithErrorPreservesFields() {
      LocalDateTime now = LocalDateTime.now();
      RuleMetadata original =
          new RuleMetadata("2.0", now, null, RuleMetadata.RuleStatus.ACTIVE, null, 10, 5.0);

      RuleMetadata errored = original.withError("timeout");

      assertThat(errored.getVersion()).isEqualTo("2.0");
      assertThat(errored.getLoadedAt()).isEqualTo(now);
      assertThat(errored.getExecutionCount()).isEqualTo(10);
      assertThat(errored.getAverageExecutionTimeMs()).isEqualTo(5.0);
    }
  }

  @Nested
  @DisplayName("withExecution")
  class WithExecution {

    @Test
    @DisplayName("first execution sets count to 1 and average to execution time")
    void testFirstExecution() {
      RuleMetadata original = RuleMetadata.createNew();

      RuleMetadata after = original.withExecution(15.0);

      assertThat(after.getExecutionCount()).isEqualTo(1);
      assertThat(after.getAverageExecutionTimeMs()).isEqualTo(15.0);
    }

    @Test
    @DisplayName("second execution calculates running average correctly")
    void testSecondExecution() {
      RuleMetadata after = RuleMetadata.createNew().withExecution(10.0).withExecution(20.0);

      assertThat(after.getExecutionCount()).isEqualTo(2);
      assertThat(after.getAverageExecutionTimeMs()).isCloseTo(15.0, within(0.001));
    }

    @Test
    @DisplayName("multiple executions calculate correct running average")
    void testMultipleExecutions() {
      RuleMetadata metadata = RuleMetadata.createNew();
      // 10 + 20 + 30 = 60, avg = 20
      metadata = metadata.withExecution(10.0);
      metadata = metadata.withExecution(20.0);
      metadata = metadata.withExecution(30.0);

      assertThat(metadata.getExecutionCount()).isEqualTo(3);
      assertThat(metadata.getAverageExecutionTimeMs()).isCloseTo(20.0, within(0.001));
    }

    @Test
    @DisplayName("handles zero execution time")
    void testZeroExecutionTime() {
      RuleMetadata after = RuleMetadata.createNew().withExecution(0.0);

      assertThat(after.getExecutionCount()).isEqualTo(1);
      assertThat(after.getAverageExecutionTimeMs()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("preserves other fields during execution tracking")
    void testWithExecutionPreservesFields() {
      LocalDateTime now = LocalDateTime.now();
      RuleMetadata original =
          new RuleMetadata("2.0", now, null, RuleMetadata.RuleStatus.ACTIVE, null, 0, 0.0);

      RuleMetadata after = original.withExecution(5.0);

      assertThat(after.getVersion()).isEqualTo("2.0");
      assertThat(after.getLoadedAt()).isEqualTo(now);
      assertThat(after.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    }
  }

  @Nested
  @DisplayName("withLastModified")
  class WithLastModified {

    @Test
    @DisplayName("converts non-null Instant to UTC LocalDateTime")
    void testWithLastModifiedNonNull() {
      Instant instant = Instant.parse("2026-01-15T10:30:00Z");
      RuleMetadata metadata = RuleMetadata.createNew().withLastModified(instant);

      assertThat(metadata.getLastModified()).isNotNull();
      assertThat(metadata.getLastModified())
          .isEqualTo(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("sets lastModified to null when Instant is null")
    void testWithLastModifiedNull() {
      // First set a non-null lastModified, then set to null
      Instant instant = Instant.now();
      RuleMetadata withDate = RuleMetadata.createNew().withLastModified(instant);
      assertThat(withDate.getLastModified()).isNotNull();

      RuleMetadata cleared = withDate.withLastModified(null);
      assertThat(cleared.getLastModified()).isNull();
    }

    @Test
    @DisplayName("preserves other fields when setting lastModified")
    void testWithLastModifiedPreservesFields() {
      LocalDateTime now = LocalDateTime.now();
      RuleMetadata original =
          new RuleMetadata("3.0", now, null, RuleMetadata.RuleStatus.ACTIVE, "test", 5, 2.5);

      RuleMetadata updated = original.withLastModified(Instant.now());

      assertThat(updated.getVersion()).isEqualTo("3.0");
      assertThat(updated.getLoadedAt()).isEqualTo(now);
      assertThat(updated.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(updated.getErrorMessage()).isEqualTo("test");
      assertThat(updated.getExecutionCount()).isEqualTo(5);
      assertThat(updated.getAverageExecutionTimeMs()).isEqualTo(2.5);
    }
  }

  @Nested
  @DisplayName("toString")
  class ToString {

    @Test
    @DisplayName("contains key fields in string representation")
    void testToStringContainsFields() {
      RuleMetadata metadata =
          new RuleMetadata(
              "1.0",
              LocalDateTime.of(2026, 1, 1, 12, 0),
              null,
              RuleMetadata.RuleStatus.ACTIVE,
              null,
              5,
              3.2);

      String result = metadata.toString();

      assertThat(result)
          .startsWith("RuleMetadata{")
          .contains("version='1.0'")
          .contains("status=ACTIVE")
          .contains("executionCount=5")
          .contains("averageExecutionTimeMs=3.2");
    }
  }

  @Nested
  @DisplayName("RuleStatus enum")
  class RuleStatusEnum {

    @Test
    @DisplayName("has all expected values")
    void testAllEnumValues() {
      RuleMetadata.RuleStatus[] values = RuleMetadata.RuleStatus.values();

      assertThat(values)
          .hasSize(4)
          .containsExactly(
              RuleMetadata.RuleStatus.LOADING,
              RuleMetadata.RuleStatus.ACTIVE,
              RuleMetadata.RuleStatus.ERROR,
              RuleMetadata.RuleStatus.DISABLED);
    }

    @Test
    @DisplayName("valueOf returns correct enum constant")
    void testValueOf() {
      assertThat(RuleMetadata.RuleStatus.valueOf("LOADING"))
          .isEqualTo(RuleMetadata.RuleStatus.LOADING);
      assertThat(RuleMetadata.RuleStatus.valueOf("ACTIVE"))
          .isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(RuleMetadata.RuleStatus.valueOf("ERROR")).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(RuleMetadata.RuleStatus.valueOf("DISABLED"))
          .isEqualTo(RuleMetadata.RuleStatus.DISABLED);
    }
  }
}
