package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("DrlSanitizer")
class DrlSanitizerTest {

  private DrlSanitizer sanitizer;

  @BeforeEach
  void setUp() {
    sanitizer = new DrlSanitizer();
  }

  @Nested
  @DisplayName("Allowed Rules")
  class AllowedRules {

    @Test
    @DisplayName("accepts a standard business rule with Map operations")
    void testAcceptsStandardRule() {
      String drl =
          """
          package com.company.rules.pricing

          import java.util.Map

          rule "Simple Discount"
          when
              $data : Map(this["amount"] != null)
          then
              double amount = ((Number) $data.get("amount")).doubleValue();
              $data.put("discount", amount * 0.10);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("pricing.discount", drl);

      assertThat(result.isAccepted()).isTrue();
      assertThat(result.getViolations()).isEmpty();
    }

    static Stream<String> allowedDrls() {
      return Stream.of(
          """
          package com.company.rules.test

          import java.util.Map
          import java.util.List
          import java.util.ArrayList
          import java.util.HashMap

          rule "Collections Rule"
          when
              $data : Map()
          then
              $data.put("executed", true);
          end
          """,
          """
          package com.company.rules.test

          import java.math.BigDecimal
          import java.math.RoundingMode

          rule "BigDecimal Rule"
          when
              $data : java.util.Map()
          then
              $data.put("total", new java.math.BigDecimal("99.99"));
          end
          """,
          """
          package com.company.rules.test

          import java.time.LocalDate
          import java.time.temporal.ChronoUnit

          rule "Date Rule"
          when
              $data : java.util.Map()
          then
              $data.put("today", java.time.LocalDate.now().toString());
          end
          """,
          """
          package com.company.rules.test

          rule "Minimal Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """,
          """
          package com.company.rules.test

          import java.util.Map

          rule "Conditional Rule"
          when
              $data : Map(this["amount"] != null)
          then
              double amount = ((Number) $data.get("amount")).doubleValue();
              if (amount > 100.0) {
                  $data.put("tier", "premium");
              } else {
                  $data.put("tier", "standard");
              }
          end
          """);
    }

    @ParameterizedTest(name = "accepts valid DRL [{index}]")
    @MethodSource("allowedDrls")
    void testAcceptsValidDrl(String drl) {
      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);
      assertThat(result.isAccepted()).isTrue();
    }
  }

  @Nested
  @DisplayName("Blocked Imports")
  class BlockedImports {

    static Stream<String> blockedImportLines() {
      return Stream.of(
          "import java.io.File",
          "import java.net.URL",
          "import java.lang.reflect.Method",
          "import javax.script.ScriptEngine",
          "import javax.naming.InitialContext",
          "import static java.lang.Math.pow",
          "import com.some.external.Library");
    }

    @ParameterizedTest(name = "rejects: {0}")
    @MethodSource("blockedImportLines")
    @DisplayName("rejects blocked imports")
    void testRejectsBlockedImport(String importLine) {
      String drl =
          """
          package com.company.rules.test

          %s

          rule "Test Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """
              .formatted(importLine);

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
    }
  }

  @Nested
  @DisplayName("Blocked Class References")
  class BlockedClassReferences {

    static Stream<Arguments> blockedClassReferences() {
      return Stream.of(
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "Runtime Rule"
              when $data : Map()
              then Runtime rt = Runtime.getRuntime();
              end
              """,
              "Runtime"),
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "Process Rule"
              when $data : Map()
              then new ProcessBuilder("ls").start();
              end
              """,
              "ProcessBuilder"),
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "Thread Rule"
              when $data : Map()
              then Thread.sleep(10000);
              end
              """,
              "Thread"),
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "ClassLoader Rule"
              when $data : Map()
              then ClassLoader cl = $data.getClass().getClassLoader();
              end
              """,
              "ClassLoader"));
    }

    @ParameterizedTest(name = "rejects class reference [{1}]")
    @MethodSource("blockedClassReferences")
    void testRejectsBlockedClassReference(String drl, String violationClass) {
      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);
      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains(violationClass));
    }
  }

  @Nested
  @DisplayName("Blocked Method Calls")
  class BlockedMethodCalls {

    static Stream<Arguments> blockedMethodCalls() {
      return Stream.of(
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "Exit Rule"
              when $data : Map()
              then System.exit(0);
              end
              """,
              "System.exit"),
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "Reflection Rule"
              when $data : Map()
              then Class.forName("java.lang.Runtime");
              end
              """,
              "Class.forName"),
          Arguments.of(
              """
              package com.company.rules.test
              import java.util.Map
              rule "Env Rule"
              when $data : Map()
              then String secret = System.getenv("SECRET_KEY");
              end
              """,
              "System.getenv"));
    }

    @ParameterizedTest(name = "rejects method call [{1}]")
    @MethodSource("blockedMethodCalls")
    void testRejectsBlockedMethodCall(String drl, String violationMethod) {
      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);
      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains(violationMethod));
    }

    @Test
    @DisplayName("rejects Runtime.getRuntime call")
    void testRejectsRuntimeGetRuntime() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Exec Rule"
          when
              $data : Map()
          then
              Runtime.getRuntime().exec("whoami");
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
    }
  }

  @Nested
  @DisplayName("Eval Blocking")
  class EvalBlocking {

    @Test
    @DisplayName("rejects eval() in when clause")
    void testRejectsEvalInWhen() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Eval Rule"
          when
              $data : Map()
              eval(true)
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("eval()"));
    }
  }

  @Nested
  @DisplayName("Multiple Violations")
  class MultipleViolations {

    @Test
    @DisplayName("reports all violations for a rule with multiple issues")
    void testReportsMultipleViolations() {
      String drl =
          """
          package com.company.rules.test

          import java.io.File
          import java.net.URL
          import java.util.Map

          rule "Multi-Bad Rule"
          when
              $data : Map()
          then
              Runtime.getRuntime().exec("cat /etc/passwd");
              System.exit(1);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).hasSizeGreaterThanOrEqualTo(4);
    }
  }
}
