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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    static Stream<Arguments> injectionInputs() {
      return Stream.of(
          Arguments.of("name", "Robert; DROP TABLE users;--"),
          Arguments.of("input", "noscript bypass"),
          Arguments.of("cmd", "ls | cat /etc/passwd"),
          Arguments.of("path", "../../etc/passwd; cat"));
    }

    @ParameterizedTest(name = "rejects injection in field [{0}]")
    @MethodSource("injectionInputs")
    void testValidate_InjectionPatterns_Rejected(String key, String value) {
      boolean result = validator.isValid(Map.of(key, value), context);
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

  @Nested
  @DisplayName("Null Data")
  class NullData {

    @Test
    @DisplayName("rejects null data map")
    void testValidate_NullData_Rejected() {
      assertThat(validator.isValid(null, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("Key Validation")
  class KeyValidation {

    @Test
    @DisplayName("rejects empty key")
    void testEmptyKey() {
      Map<String, Object> data = new HashMap<>();
      data.put("", "value");

      assertThat(validator.isValid(data, context)).isFalse();
    }

    @Test
    @DisplayName("rejects key exceeding max length")
    void testKeyTooLong() {
      Map<String, Object> data = new HashMap<>();
      data.put("a".repeat(101), "value");

      assertThat(validator.isValid(data, context)).isFalse();
    }

    @Test
    @DisplayName("accepts key at max length (100)")
    void testKeyAtMaxLength() {
      Map<String, Object> data = new HashMap<>();
      data.put("a".repeat(100), "value");

      assertThat(validator.isValid(data, context)).isTrue();
    }

    static Stream<String> dangerousKeys() {
      return Stream.of("javascript", "eval(", "exec(cmd)");
    }

    @ParameterizedTest(name = "rejects key [{0}] containing dangerous pattern")
    @MethodSource("dangerousKeys")
    void testKeyWithDangerousPattern(String key) {
      Map<String, Object> data = new HashMap<>();
      data.put(key, 123);
      assertThat(validator.isValid(data, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("Value Type Validation")
  class ValueTypeValidation {

    @Test
    @DisplayName("accepts null value in map")
    void testNullValue() {
      Map<String, Object> data = new HashMap<>();
      data.put("key", null);

      assertThat(validator.isValid(data, context)).isTrue();
    }

    @Test
    @DisplayName("accepts boolean true")
    void testBooleanTrue() {
      assertThat(validator.isValid(Map.of("flag", true), context)).isTrue();
    }

    @Test
    @DisplayName("accepts boolean false")
    void testBooleanFalse() {
      assertThat(validator.isValid(Map.of("flag", false), context)).isTrue();
    }

    @Test
    @DisplayName("accepts integer at boundary")
    void testIntegerAtBoundary() {
      assertThat(validator.isValid(Map.of("amount", 1_000_000), context)).isTrue();
    }

    @Test
    @DisplayName("rejects negative number exceeding max")
    void testNegativeNumberExceedsMax() {
      assertThat(validator.isValid(Map.of("amount", -1_000_001L), context)).isFalse();
    }

    @Test
    @DisplayName("accepts zero")
    void testZero() {
      assertThat(validator.isValid(Map.of("amount", 0), context)).isTrue();
    }

    @Test
    @DisplayName("accepts double value within range")
    void testDoubleValue() {
      assertThat(validator.isValid(Map.of("price", 99.99), context)).isTrue();
    }

    @Test
    @DisplayName("accepts float value within range")
    void testFloatValue() {
      assertThat(validator.isValid(Map.of("weight", 5.5f), context)).isTrue();
    }

    @Test
    @DisplayName("accepts string at max length (500)")
    void testStringAtMaxLength() {
      assertThat(validator.isValid(Map.of("text", "a".repeat(500)), context)).isTrue();
    }

    @Test
    @DisplayName("accepts empty string")
    void testEmptyStringValue() {
      assertThat(validator.isValid(Map.of("text", ""), context)).isTrue();
    }
  }

  @Nested
  @DisplayName("Other Type Validation")
  class OtherTypeValidation {

    @Test
    @DisplayName("accepts list with safe toString")
    void testListValue() {
      Map<String, Object> data = new HashMap<>();
      data.put("items", java.util.List.of(1, 2, 3));

      assertThat(validator.isValid(data, context)).isTrue();
    }

    @Test
    @DisplayName("rejects object with dangerous toString")
    void testObjectWithDangerousToString() {
      Object dangerous =
          new Object() {
            @Override
            public String toString() {
              return "exec(malicious)";
            }
          };

      Map<String, Object> data = new HashMap<>();
      data.put("obj", dangerous);

      assertThat(validator.isValid(data, context)).isFalse();
    }

    @Test
    @DisplayName("rejects object with long toString")
    void testObjectWithLongToString() {
      Object longObj =
          new Object() {
            @Override
            public String toString() {
              return "x".repeat(501);
            }
          };

      Map<String, Object> data = new HashMap<>();
      data.put("obj", longObj);

      assertThat(validator.isValid(data, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("Dangerous Pattern Detection")
  class DangerousPatternDetection {

    @Test
    @DisplayName("rejects javascript pattern (case insensitive)")
    void testJavascriptPattern() {
      assertThat(validator.isValid(Map.of("field", "JAVASCRIPT code"), context)).isFalse();
    }

    @Test
    @DisplayName("rejects eval pattern with space")
    void testEvalPattern() {
      assertThat(validator.isValid(Map.of("field", "eval (code)"), context)).isFalse();
    }

    @Test
    @DisplayName("rejects exec pattern")
    void testExecPattern() {
      assertThat(validator.isValid(Map.of("field", "EXEC (cmd)"), context)).isFalse();
    }
  }
}
