package com.company.drools.api.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.company.drools.config.ValidationConfig;
import com.company.drools.testutil.ValidationConfigTestHelper;
import jakarta.validation.ConstraintValidatorContext;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("RuleIdValidator")
@ExtendWith(MockitoExtension.class)
class RuleIdValidatorTest {

  private RuleIdValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() throws Exception {
    validator = new RuleIdValidator();

    ValidationConfig config = ValidationConfigTestHelper.createTestValidationConfig();
    Field configField = RuleIdValidator.class.getDeclaredField("validationConfig");
    configField.setAccessible(true);
    configField.set(validator, config);

    context = mock(ConstraintValidatorContext.class);
    ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder =
        mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
    lenient()
        .when(context.buildConstraintViolationWithTemplate(anyString()))
        .thenReturn(violationBuilder);
  }

  @Nested
  @DisplayName("Null and Empty Values")
  class NullAndEmpty {

    @Test
    @DisplayName("rejects null rule ID")
    void testNull() {
      assertThat(validator.isValid(null, context)).isFalse();
    }

    @Test
    @DisplayName("rejects empty string")
    void testEmpty() {
      assertThat(validator.isValid("", context)).isFalse();
    }

    @Test
    @DisplayName("rejects whitespace-only string")
    void testWhitespace() {
      assertThat(validator.isValid("   ", context)).isFalse();
    }
  }

  @Nested
  @DisplayName("Length Validation")
  class LengthValidation {

    @Test
    @DisplayName("accepts rule ID at max length (255)")
    void testAtMaxLength() {
      String ruleId = "a".repeat(255);
      assertThat(validator.isValid(ruleId, context)).isTrue();
    }

    @Test
    @DisplayName("rejects rule ID exceeding max length (256)")
    void testOverMaxLength() {
      String ruleId = "a".repeat(256);
      assertThat(validator.isValid(ruleId, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("Valid Format")
  class ValidFormat {

    @Test
    @DisplayName("accepts dot-separated rule ID")
    void testDotSeparated() {
      assertThat(validator.isValid("pricing.discount.simple", context)).isTrue();
    }

    @Test
    @DisplayName("accepts dashes in rule ID")
    void testDashes() {
      assertThat(validator.isValid("rule-name", context)).isTrue();
    }

    @Test
    @DisplayName("accepts underscores in rule ID")
    void testUnderscores() {
      assertThat(validator.isValid("rule_name", context)).isTrue();
    }

    @Test
    @DisplayName("accepts alphanumeric rule ID")
    void testAlphanumeric() {
      assertThat(validator.isValid("rule123", context)).isTrue();
    }

    @Test
    @DisplayName("accepts mixed allowed characters")
    void testMixedAllowed() {
      assertThat(validator.isValid("rule-1_2.3", context)).isTrue();
    }
  }

  @Nested
  @DisplayName("Invalid Format")
  class InvalidFormat {

    @Test
    @DisplayName("rejects spaces")
    void testSpaces() {
      assertThat(validator.isValid("rule name", context)).isFalse();
    }

    @Test
    @DisplayName("rejects special characters")
    void testSpecialChars() {
      assertThat(validator.isValid("rule@name", context)).isFalse();
      assertThat(validator.isValid("rule#name", context)).isFalse();
      assertThat(validator.isValid("rule%name", context)).isFalse();
      assertThat(validator.isValid("rule+name", context)).isFalse();
    }

    @Test
    @DisplayName("rejects brackets and parentheses")
    void testBrackets() {
      assertThat(validator.isValid("rule(name)", context)).isFalse();
      assertThat(validator.isValid("rule[name]", context)).isFalse();
    }

    @Test
    @DisplayName("rejects equals sign")
    void testEquals() {
      assertThat(validator.isValid("rule=name", context)).isFalse();
    }
  }

  @Nested
  @DisplayName("Path Traversal Prevention")
  class PathTraversal {

    @Test
    @DisplayName("rejects double dots")
    void testDoubleDots() {
      // Note: ".." contains only allowed chars by regex, but the path traversal
      // check catches it. However "a..b" passes the regex but has ".." in it.
      // The regex ^[a-zA-Z0-9._-]+$ allows dots, so "a..b" passes format check.
      // The path traversal check on line 48 catches it.
      assertThat(validator.isValid("a..b", context)).isFalse();
    }

    @Test
    @DisplayName("rejects forward slash at start")
    void testForwardSlash() {
      // Forward slash is not in [a-zA-Z0-9._-], so regex catches it first
      assertThat(validator.isValid("/absolute/path", context)).isFalse();
    }

    @Test
    @DisplayName("rejects backslash")
    void testBackslash() {
      // Backslash is not in [a-zA-Z0-9._-], so regex catches it first
      assertThat(validator.isValid("path\\traversal", context)).isFalse();
    }
  }
}
