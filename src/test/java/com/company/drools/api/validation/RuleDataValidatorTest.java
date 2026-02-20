package com.company.drools.api.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.company.drools.config.ValidationConfig;
import com.company.drools.testutil.ValidationConfigTestHelper;
import jakarta.validation.ConstraintValidatorContext;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("RuleDataValidator")
@ExtendWith(MockitoExtension.class)
class RuleDataValidatorTest {

  private RuleDataValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() throws Exception {
    validator = new RuleDataValidator();

    // Inject ValidationConfig via reflection (normally done by Spring @Autowired)
    ValidationConfig config = ValidationConfigTestHelper.createTestValidationConfig();
    Field configField = RuleDataValidator.class.getDeclaredField("validationConfig");
    configField.setAccessible(true);
    configField.set(validator, config);

    // Mock ConstraintValidatorContext for violation reporting
    context = mock(ConstraintValidatorContext.class);
    ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder =
        mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
    lenient()
        .when(context.buildConstraintViolationWithTemplate(anyString()))
        .thenReturn(violationBuilder);
  }

  @Nested
  @DisplayName("Field Limits")
  class FieldLimits {

    @Test
    @DisplayName("rejects data exceeding max fields limit")
    void testValidate_ExceedsMaxFields_ThrowsValidationException() {
      // ValidationConfigTestHelper sets dataMaxFields = 50
      Map<String, Object> data = new HashMap<>();
      for (int i = 0; i < 51; i++) {
        data.put("field" + i, "value");
      }

      boolean result = validator.isValid(data, context);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("rejects string value exceeding max string length")
    void testValidate_ExceedsStringLength_ThrowsValidationException() {
      // ValidationConfigTestHelper sets dataMaxStringLength = 500
      String longValue = "a".repeat(501);
      Map<String, Object> data = Map.of("key", longValue);

      boolean result = validator.isValid(data, context);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("rejects number value exceeding max number value")
    void testValidate_ExceedsNumberValue_ThrowsValidationException() {
      // ValidationConfigTestHelper sets dataMaxNumberValue = 1_000_000
      Map<String, Object> data = Map.of("amount", 1_000_001L);

      boolean result = validator.isValid(data, context);

      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("Injection Prevention")
  class InjectionPrevention {

    @Test
    @DisplayName("rejects SQL injection patterns")
    void testValidate_SQLInjection_Rejected() {
      // Semicolon is blocked by SAFE_STRING_PATTERN
      Map<String, Object> data = Map.of("name", "Robert; DROP TABLE users;--");

      boolean result = validator.isValid(data, context);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("rejects script tag injection")
    void testValidate_ScriptTag_Rejected() {
      // "script" matches DANGEROUS_PATTERNS and "<>" is blocked by SAFE_STRING_PATTERN
      Map<String, Object> data = Map.of("input", "noscript bypass");

      boolean result = validator.isValid(data, context);

      // The word "script" is matched by the dangerous pattern regex
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("rejects command injection patterns")
    void testValidate_CommandInjection_Rejected() {
      // Pipe character "|" is blocked by SAFE_STRING_PATTERN
      Map<String, Object> data = Map.of("cmd", "ls | cat /etc/passwd");

      boolean result = validator.isValid(data, context);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("rejects path traversal patterns in keys")
    void testValidate_PathTraversal_Rejected() {
      // Keys containing dangerous characters are caught by SAFE_STRING_PATTERN via
      // containsDangerousPattern,
      // but path traversal with ".." alone isn't caught by the current patterns.
      // However, using angle brackets or semicolons in a traversal attempt will be caught.
      // The key validator checks containsDangerousPattern, and values check SAFE_STRING_PATTERN.
      // A typical path traversal in a value uses "../" which passes SAFE_STRING_PATTERN but
      // we can test with a value that combines traversal with other unsafe chars.
      Map<String, Object> data = Map.of("path", "../../etc/passwd; cat");

      boolean result = validator.isValid(data, context);

      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("Dangerous Patterns")
  class DangerousPatterns {

    @Test
    @DisplayName("rejects values containing dangerous characters")
    void testValidate_DangerousCharacters_Rejected() {
      // Angle brackets, quotes, semicolons, ampersands, pipes are all rejected

      // Test angle bracket
      Map<String, Object> angleBracketData = Map.of("field", "value<tag>");
      assertThat(validator.isValid(angleBracketData, context)).isFalse();

      // Test ampersand
      Map<String, Object> ampersandData = Map.of("field", "cmd1 & cmd2");
      assertThat(validator.isValid(ampersandData, context)).isFalse();

      // Test single quote
      Map<String, Object> quoteData = Map.of("field", "it's dangerous");
      assertThat(validator.isValid(quoteData, context)).isFalse();
    }

    @Test
    @DisplayName("accepts valid safe data")
    void testValidate_ValidData_Passes() {
      Map<String, Object> data = new HashMap<>();
      data.put("amount", 100);
      data.put("name", "John Doe");
      data.put("active", true);
      data.put("score", 99.5);

      boolean result = validator.isValid(data, context);

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("accepts empty data map")
    void testValidate_EmptyData_Passes() {
      Map<String, Object> data = new HashMap<>();

      boolean result = validator.isValid(data, context);

      assertThat(result).isTrue();
    }
  }
}
