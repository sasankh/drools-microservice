package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("accepts rules importing java.util classes")
    void testAcceptsJavaUtilImports() {
      String drl =
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
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("accepts rules importing java.math classes")
    void testAcceptsJavaMathImports() {
      String drl =
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
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("accepts rules importing java.time classes")
    void testAcceptsJavaTimeImports() {
      String drl =
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
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("accepts rules with no imports")
    void testAcceptsNoImports() {
      String drl =
          """
          package com.company.rules.test

          rule "Minimal Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("accepts rules with if/else control flow in then block")
    void testAcceptsControlFlow() {
      String drl =
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
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isTrue();
    }
  }

  @Nested
  @DisplayName("Blocked Imports")
  class BlockedImports {

    @Test
    @DisplayName("rejects java.io import")
    void testRejectsJavaIo() {
      String drl =
          """
          package com.company.rules.test

          import java.io.File
          import java.util.Map

          rule "File Read"
          when
              $data : Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("java.io.File"));
    }

    @Test
    @DisplayName("rejects java.net import")
    void testRejectsJavaNet() {
      String drl =
          """
          package com.company.rules.test

          import java.net.URL

          rule "Network Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("java.net.URL"));
    }

    @Test
    @DisplayName("rejects java.lang.reflect import")
    void testRejectsReflection() {
      String drl =
          """
          package com.company.rules.test

          import java.lang.reflect.Method

          rule "Reflection Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("java.lang.reflect.Method"));
    }

    @Test
    @DisplayName("rejects javax.script import")
    void testRejectsScriptEngine() {
      String drl =
          """
          package com.company.rules.test

          import javax.script.ScriptEngine

          rule "Script Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("rejects javax.naming import (JNDI)")
    void testRejectsJndi() {
      String drl =
          """
          package com.company.rules.test

          import javax.naming.InitialContext

          rule "JNDI Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("rejects static imports")
    void testRejectsStaticImports() {
      String drl =
          """
          package com.company.rules.test

          import static java.lang.Math.pow

          rule "Static Import Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("Static imports"));
    }

    @Test
    @DisplayName("rejects unknown/unallowed imports")
    void testRejectsUnknownImports() {
      String drl =
          """
          package com.company.rules.test

          import com.some.external.Library

          rule "Unknown Import Rule"
          when
              $data : java.util.Map()
          then
              $data.put("executed", true);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("not in allowlist"));
    }
  }

  @Nested
  @DisplayName("Blocked Class References")
  class BlockedClassReferences {

    @Test
    @DisplayName("rejects Runtime reference in then block")
    void testRejectsRuntime() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Runtime Rule"
          when
              $data : Map()
          then
              Runtime rt = Runtime.getRuntime();
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("Runtime"));
    }

    @Test
    @DisplayName("rejects ProcessBuilder reference")
    void testRejectsProcessBuilder() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Process Rule"
          when
              $data : Map()
          then
              new ProcessBuilder("ls").start();
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("ProcessBuilder"));
    }

    @Test
    @DisplayName("rejects Thread reference")
    void testRejectsThread() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Thread Rule"
          when
              $data : Map()
          then
              Thread.sleep(10000);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("Thread"));
    }

    @Test
    @DisplayName("rejects ClassLoader reference")
    void testRejectsClassLoader() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "ClassLoader Rule"
          when
              $data : Map()
          then
              ClassLoader cl = $data.getClass().getClassLoader();
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("ClassLoader"));
    }
  }

  @Nested
  @DisplayName("Blocked Method Calls")
  class BlockedMethodCalls {

    @Test
    @DisplayName("rejects System.exit call")
    void testRejectsSystemExit() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Exit Rule"
          when
              $data : Map()
          then
              System.exit(0);
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("System.exit"));
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

    @Test
    @DisplayName("rejects Class.forName call")
    void testRejectsClassForName() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Reflection Rule"
          when
              $data : Map()
          then
              Class.forName("java.lang.Runtime");
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("Class.forName"));
    }

    @Test
    @DisplayName("rejects System.getenv call")
    void testRejectsSystemGetenv() {
      String drl =
          """
          package com.company.rules.test

          import java.util.Map

          rule "Env Rule"
          when
              $data : Map()
          then
              String secret = System.getenv("SECRET_KEY");
          end
          """;

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("System.getenv"));
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
      assertThat(result.getViolations().size()).isGreaterThanOrEqualTo(4);
    }
  }
}
