package com.company.drools.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.BaseUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@DisplayName("LogSanitizer")
@MockitoSettings(strictness = Strictness.LENIENT)
class LogSanitizerTest extends BaseUnitTest {

  @Nested
  @DisplayName("Sensitive Data Detection")
  class SensitiveDataDetection {

    @Test
    @DisplayName("sanitizes credit card numbers in data values")
    void testSanitize_CreditCard_Masked() {
      Map<String, Object> data = new HashMap<>();
      data.put("payment", "4111-1111-1111-1111");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("payment", "****-****-****-1111");
    }

    @Test
    @DisplayName("sanitizes SSN values")
    void testSanitize_SSN_Masked() {
      Map<String, Object> data = new HashMap<>();
      data.put("identifier", "123-45-6789");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("identifier", "***-**-6789");
    }

    @Test
    @DisplayName("sanitizes email when key contains email")
    void testSanitize_Email_Masked() {
      Map<String, Object> data = new HashMap<>();
      data.put("user_email", "user@example.com");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("user_email", "u***@example.com");
    }

    @Test
    @DisplayName("redacts values for keys matching sensitive field names")
    void testSanitize_SensitiveKey_Redacted() {
      Map<String, Object> data = new HashMap<>();
      data.put("password", "mysecretpassword123");
      data.put("ssn", "123-45-6789");
      data.put("api_key", "sk-abcdef123456");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result)
          .containsEntry("password", "[REDACTED]")
          .containsEntry("ssn", "[REDACTED]")
          .containsEntry("api_key", "[REDACTED]");
    }
  }

  @Nested
  @DisplayName("Pattern Matching")
  class PatternMatching {

    @Test
    @DisplayName("sanitizes multiple credit card numbers in a message")
    void testSanitize_MultipleCreditCards_AllMasked() {
      String message = "Cards: 4111-1111-1111-1111 and 5500 0000 0000 0004";

      String result = LogSanitizer.sanitizeMessage(message);

      assertThat(result)
          .doesNotContain("4111-1111-1111-1111")
          .doesNotContain("5500 0000 0000 0004")
          .contains("[CC-REDACTED]");
    }

    @Test
    @DisplayName("sanitizes mixed sensitive data in a message")
    void testSanitize_MixedSensitiveData_AllMasked() {
      String message = "CC: 4111111111111111 SSN: 123-45-6789";

      String result = LogSanitizer.sanitizeMessage(message);

      assertThat(result).doesNotContain("4111111111111111").doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("leaves non-sensitive data unchanged")
    void testSanitize_NoSensitiveData_Unchanged() {
      Map<String, Object> data = new HashMap<>();
      data.put("amount", 100);
      data.put("currency", "USD");
      data.put("status", "active");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result)
          .containsEntry("amount", 100)
          .containsEntry("currency", "USD")
          .containsEntry("status", "active");
    }

    @Test
    @DisplayName("handles null and empty inputs gracefully")
    void testSanitize_NullOrEmpty_HandledGracefully() {
      assertThat(LogSanitizer.sanitizeDataMap(null)).isNull();
      assertThat(LogSanitizer.sanitizeDataMap(new HashMap<>())).isEmpty();
      assertThat(LogSanitizer.sanitizeMessage(null)).isNull();
      assertThat(LogSanitizer.sanitizeMessage("")).isEmpty();
      assertThat(LogSanitizer.sanitizeValue(null, "value")).isEqualTo("value");
      assertThat(LogSanitizer.sanitizeValue("key", null)).isNull();
    }
  }

  @Nested
  @DisplayName("Edge Case Branch Coverage")
  class EdgeCases {

    @Test
    @DisplayName("masks credit card without separators")
    void testCreditCardNoSeparators() {
      Map<String, Object> data = new HashMap<>();
      data.put("payment", "4111111111111111");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("payment", "****-****-****-1111");
    }

    @Test
    @DisplayName("SSN without separators is NOT masked (avoids false positives on 9-digit numbers)")
    void testSsnNoSeparators() {
      Map<String, Object> data = new HashMap<>();
      data.put("identifier", "123456789");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      // 9-digit numbers without dashes/spaces should pass through (could be order IDs, etc.)
      assertThat(result).containsEntry("identifier", "123456789");
    }

    @Test
    @DisplayName("email key with non-email value passes through")
    void testEmailKeyNonEmailValue() {
      Map<String, Object> data = new HashMap<>();
      data.put("email", "not-an-email");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("email", "not-an-email");
    }

    @Test
    @DisplayName("email with @ at position 0 or 1 is redacted")
    void testEmailAtSymbolAtStart() {
      Map<String, Object> data = new HashMap<>();
      data.put("email", "@example.com");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("email", "[REDACTED]");
    }

    @Test
    @DisplayName("non-sensitive key returns value unchanged")
    void testNonSensitiveKey() {
      Object result = LogSanitizer.sanitizeValue("customfield", "safe value");

      assertThat(result).isEqualTo("safe value");
    }

    @Test
    @DisplayName("safeDataRepresentation with large map truncates")
    void testSafeDataRepresentationLargeMap() {
      Map<String, Object> data = new HashMap<>();
      for (int i = 0; i < 15; i++) {
        data.put("field" + i, "value" + i);
      }

      String result = LogSanitizer.safeDataRepresentation(data);

      assertThat(result).contains("more fields)");
    }

    @Test
    @DisplayName("safeDataRepresentation with null returns null string")
    void testSafeDataRepresentationNull() {
      assertThat(LogSanitizer.safeDataRepresentation(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("safeDataRepresentation with empty map returns {}")
    void testSafeDataRepresentationEmpty() {
      assertThat(LogSanitizer.safeDataRepresentation(new HashMap<>())).isEqualTo("{}");
    }

    @Test
    @DisplayName("safeDataRepresentation with small map shows all keys")
    void testSafeDataRepresentationSmallMap() {
      Map<String, Object> data = new HashMap<>();
      data.put("amount", 100);
      data.put("currency", "USD");

      String result = LogSanitizer.safeDataRepresentation(data);

      assertThat(result).contains("amount").contains("currency").doesNotContain("more fields");
    }
  }

  @Nested
  @DisplayName("False Positive Prevention")
  class FalsePositivePrevention {

    @Test
    @DisplayName("'shipping' key is not redacted (no false positive on 'pin' substring)")
    void testShippingNotRedacted() {
      Map<String, Object> data = new HashMap<>();
      data.put("shipping", "express");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("shipping", "express");
    }

    @Test
    @DisplayName("'author' key is not redacted (no false positive on 'auth' substring)")
    void testAuthorNotRedacted() {
      Map<String, Object> data = new HashMap<>();
      data.put("author", "Jane Doe");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("author", "Jane Doe");
    }

    @Test
    @DisplayName("'pin' key is still redacted (exact word boundary)")
    void testPinStillRedacted() {
      Map<String, Object> data = new HashMap<>();
      data.put("pin", "1234");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("pin", "[REDACTED]");
    }

    @Test
    @DisplayName("'auth' key is still redacted (exact word boundary)")
    void testAuthStillRedacted() {
      Map<String, Object> data = new HashMap<>();
      data.put("auth", "bearer-xyz");

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      assertThat(result).containsEntry("auth", "[REDACTED]");
    }

    @Test
    @DisplayName("class names are not redacted in messages")
    void testClassNameNotRedacted() {
      String message = "Error in DroolsEngineService during processing";

      String result = LogSanitizer.sanitizeMessage(message);

      assertThat(result).contains("DroolsEngineService");
    }
  }

  @Nested
  @DisplayName("Nested Map Sanitization")
  class NestedMapSanitization {

    @Test
    @DisplayName("sanitizes sensitive keys in nested maps")
    void testNestedMapSensitiveKeys() {
      Map<String, Object> nested = new HashMap<>();
      nested.put("password", "secret123");
      nested.put("name", "John");

      Map<String, Object> data = new HashMap<>();
      data.put("user", nested);

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      @SuppressWarnings("unchecked")
      Map<String, Object> resultNested = (Map<String, Object>) result.get("user");
      assertThat(resultNested)
          .containsEntry("password", "[REDACTED]")
          .containsEntry("name", "John");
    }

    @Test
    @DisplayName("handles deeply nested maps")
    void testDeeplyNestedMap() {
      Map<String, Object> level3 = new HashMap<>();
      level3.put("token", "abc123");

      Map<String, Object> level2 = new HashMap<>();
      level2.put("config", level3);

      Map<String, Object> level1 = new HashMap<>();
      level1.put("settings", level2);

      Map<String, Object> data = new HashMap<>();
      data.put("app", level1);

      Map<String, Object> result = LogSanitizer.sanitizeDataMap(data);

      @SuppressWarnings("unchecked")
      Map<String, Object> r1 = (Map<String, Object>) result.get("app");
      @SuppressWarnings("unchecked")
      Map<String, Object> r2 = (Map<String, Object>) r1.get("settings");
      @SuppressWarnings("unchecked")
      Map<String, Object> r3 = (Map<String, Object>) r2.get("config");
      assertThat(r3).containsEntry("token", "[REDACTED]");
    }
  }
}
