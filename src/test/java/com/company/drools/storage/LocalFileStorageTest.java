package com.company.drools.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.core.model.Rule;
import com.company.drools.testutil.RuleTestUtils;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("LocalFileStorage")
class LocalFileStorageTest {

  @TempDir Path tempDir;

  private LocalFileStorage localFileStorage;

  @BeforeEach
  void setUp() throws Exception {
    localFileStorage = new LocalFileStorage();

    // Set rulesDirectory via reflection since it's @Value-injected
    Field rulesDirectoryField = LocalFileStorage.class.getDeclaredField("rulesDirectory");
    rulesDirectoryField.setAccessible(true);
    rulesDirectoryField.set(localFileStorage, tempDir.toString());
  }

  // ========================================================================
  // Happy Path Tests
  // ========================================================================

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("saveRule and getRule round-trip succeeds")
    void testSaveAndGetRule_Success() {
      // Given
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");

      // When
      localFileStorage.saveRule(rule);
      Optional<Rule> result = localFileStorage.getRule("pricing.discount.simple");

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getRuleId()).isEqualTo("pricing.discount.simple");
      assertThat(result.get().getContent()).isEqualTo(rule.getContent());
      assertThat(result.get().getMetadata()).isNotNull();
      assertThat(result.get().getMetadata().getLastModified()).isNotNull();

      // Verify the file was actually created on disk
      Path expectedFile = tempDir.resolve("pricing/discount/simple.drl");
      assertThat(Files.exists(expectedFile)).isTrue();
    }

    @Test
    @DisplayName("getAllRules returns multiple saved rules")
    void testGetAllRules_ReturnsMultipleRules() {
      // Given
      Rule rule1 = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      Rule rule2 = RuleTestUtils.createSimpleRule("validation.input.basic");
      Rule rule3 = RuleTestUtils.createSimpleRule("pricing.discount.vip");

      localFileStorage.saveRule(rule1);
      localFileStorage.saveRule(rule2);
      localFileStorage.saveRule(rule3);

      // When
      List<Rule> rules = localFileStorage.getAllRules();

      // Then
      assertThat(rules).hasSize(3);
      assertThat(rules)
          .extracting(Rule::getRuleId)
          .containsExactlyInAnyOrder(
              "pricing.discount.simple", "validation.input.basic", "pricing.discount.vip");
    }

    @Test
    @DisplayName("deleteRule removes existing rule successfully")
    void testDeleteRule_Success() {
      // Given
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      localFileStorage.saveRule(rule);

      // Verify rule exists before deletion
      assertThat(localFileStorage.ruleExists("pricing.discount.simple")).isTrue();

      // When
      localFileStorage.deleteRule("pricing.discount.simple");

      // Then
      assertThat(localFileStorage.ruleExists("pricing.discount.simple")).isFalse();
      assertThat(localFileStorage.getRule("pricing.discount.simple")).isEmpty();

      Path expectedFile = tempDir.resolve("pricing/discount/simple.drl");
      assertThat(Files.exists(expectedFile)).isFalse();
    }
  }

  // ========================================================================
  // Error Handling Tests
  // ========================================================================

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("getRule returns empty Optional when rule does not exist")
    void testGetRule_NotFound_ReturnsEmpty() {
      // When
      Optional<Rule> result = localFileStorage.getRule("nonexistent.rule");

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("saveRule throws RuntimeException on IO error")
    void testSaveRule_IOError_ThrowsException() throws Exception {
      // Given - set rulesDirectory to an invalid path (a file, not a directory)
      Path blockingFile = tempDir.resolve("not-a-directory");
      Files.writeString(blockingFile, "blocking");

      Field rulesDirectoryField = LocalFileStorage.class.getDeclaredField("rulesDirectory");
      rulesDirectoryField.setAccessible(true);
      rulesDirectoryField.set(localFileStorage, blockingFile.toString());

      Rule rule = RuleTestUtils.createSimpleRule("some.rule");

      // When / Then
      assertThatThrownBy(() -> localFileStorage.saveRule(rule))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to save rule")
          .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("deleteRule throws RuleNotFoundException for non-existent rule")
    void testDeleteRule_NotFound_ThrowsRuleNotFoundException() {
      // When / Then
      assertThatThrownBy(() -> localFileStorage.deleteRule("nonexistent.rule"))
          .isInstanceOf(RuleNotFoundException.class)
          .hasMessageContaining("Rule not found");
    }
  }

  // ========================================================================
  // File System Operations Tests
  // ========================================================================

  @Nested
  @DisplayName("File System Operations")
  class FileSystemOperations {

    @Test
    @DisplayName("getTotalRuleCount counts all saved DRL files")
    void testGetTotalRuleCount_CountsFiles() {
      // Given - no rules yet
      assertThat(localFileStorage.getTotalRuleCount()).isZero();

      // When
      localFileStorage.saveRule(RuleTestUtils.createSimpleRule("pricing.discount.simple"));
      localFileStorage.saveRule(RuleTestUtils.createSimpleRule("validation.input.basic"));
      localFileStorage.saveRule(RuleTestUtils.createSimpleRule("pricing.discount.vip"));

      // Then
      assertThat(localFileStorage.getTotalRuleCount()).isEqualTo(3);

      // When - delete one
      localFileStorage.deleteRule("pricing.discount.simple");

      // Then
      assertThat(localFileStorage.getTotalRuleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("rule ID dots are transformed to directory slashes with .drl extension")
    void testRuleIdTransformation_DotsToSlashes() {
      // Given
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.black-friday");

      // When
      localFileStorage.saveRule(rule);

      // Then - verify the file was written at the expected path
      Path expectedFile = tempDir.resolve("pricing/discount/black-friday.drl");
      assertThat(Files.exists(expectedFile)).isTrue();

      // Verify subdirectories were created
      assertThat(Files.isDirectory(tempDir.resolve("pricing"))).isTrue();
      assertThat(Files.isDirectory(tempDir.resolve("pricing/discount"))).isTrue();

      // Verify round-trip: getRule with the same ID retrieves it
      Optional<Rule> retrieved = localFileStorage.getRule("pricing.discount.black-friday");
      assertThat(retrieved).isPresent();
      assertThat(retrieved.get().getRuleId()).isEqualTo("pricing.discount.black-friday");
      assertThat(retrieved.get().getContent()).isEqualTo(rule.getContent());

      // Verify getRuleIds transforms paths back to dot-separated IDs
      List<String> ruleIds = localFileStorage.getRuleIds();
      assertThat(ruleIds).containsExactly("pricing.discount.black-friday");
    }
  }
}
