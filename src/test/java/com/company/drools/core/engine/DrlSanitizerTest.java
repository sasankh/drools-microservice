package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Nested
  @DisplayName("Normalization-resistant checks")
  class NormalizationResistant {

    private static String wrap(String header, String body) {
      return """
          package com.company.rules.test

          import java.util.Map
          %s

          rule "Test Rule"
          when
              $d : Map()
          then
              %s
          end
          """
          .formatted(header, body);
    }

    static Stream<Arguments> rejectedDrls() {
      return Stream.of(
          Arguments.of(
              "import with trailing // comment", wrap("import java.io.File // x", ""), "java.io."),
          Arguments.of(
              "import with trailing block comment",
              wrap("import java.io.File /* c */", ""),
              "java.io."),
          Arguments.of("space before dot", wrap("", "System .exit(0);"), "System.exit"),
          Arguments.of(
              "call split across lines",
              wrap("", "String v = System\n    .getenv(\"X\");"),
              "System.getenv"),
          Arguments.of(
              "FQN java.nio in body",
              wrap(
                  "",
                  "String s = java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"));"),
              "java.nio."),
          Arguments.of(
              "FQN java.lang.reflect in body",
              wrap("", "java.lang.reflect.Method m = null;"),
              "java.lang.reflect."),
          Arguments.of(
              "java.util.concurrent import despite java.util. allowlist",
              wrap("import java.util.concurrent.Executors;", ""),
              "java.util.concurrent."),
          Arguments.of(
              "java.util.concurrent FQN in body",
              wrap("", "java.util.concurrent.Executors.newFixedThreadPool(1);"),
              "java.util.concurrent."),
          Arguments.of(
              "ServiceLoader", wrap("", "ServiceLoader.load(Object.class);"), "ServiceLoader"),
          Arguments.of(
              "getClassLoader",
              wrap("", "Object cl = $d.getClass().getClassLoader();"),
              ".getClassLoader"),
          Arguments.of(
              "FQN java.lang.System", wrap("", "java.lang.System.exit(0);"), "java.lang.System"),
          Arguments.of(
              "System.getProperties",
              wrap("", "Object p = System.getProperties();"),
              "System.getProperties"),
          Arguments.of("System.setOut", wrap("", "System.setOut(null);"), "System.setOut"),
          Arguments.of(
              "getDeclared via getClass",
              wrap("", "Object f = $d.getClass().getDeclaredFields();"),
              ".getClass().getDeclared"),
          Arguments.of(
              "MethodHandles", wrap("", "Object l = MethodHandles.lookup();"), "MethodHandles"));
    }

    @ParameterizedTest(name = "rejects: {0}")
    @MethodSource("rejectedDrls")
    void rejects(String name, String drl, String expectedViolationFragment) {
      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).as(name).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains(expectedViolationFragment));
    }

    @Test
    @DisplayName("violations are de-duplicated")
    void violationsDeduplicated() {
      String drl = wrap("import java.io.File\nimport java.io.File", "java.io.File a = null;");

      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).doesNotHaveDuplicates();
      assertThat(result.getViolations()).filteredOn(v -> v.startsWith("Blocked import")).hasSize(1);
    }

    static Stream<Path> sampleRules() throws IOException {
      try (Stream<Path> files = Files.walk(Path.of("sample-rules"))) {
        List<Path> drls = files.filter(f -> f.toString().endsWith(".drl")).sorted().toList();
        assertThat(drls).hasSize(17);
        return drls.stream();
      }
    }

    @ParameterizedTest(name = "accepts sample rule {0}")
    @MethodSource("sampleRules")
    void acceptsAllSampleRules(Path drl) throws IOException {
      DrlSanitizer.SanitizationResult result =
          sanitizer.sanitize(drl.toString(), Files.readString(drl));

      assertThat(result.getViolations()).isEmpty();
      assertThat(result.isAccepted()).isTrue();
    }

    static Stream<Arguments> acceptedDrls() {
      return Stream.of(
          Arguments.of(
              "string literal mentioning System.exit",
              wrap("", "$d.put(\"note\", \"System.exit is blocked\");")),
          Arguments.of(
              "string literal mentioning java.io. and Runtime",
              wrap("", "$d.put(\"note\", \"no java.io.File or Runtime here\");")),
          Arguments.of(
              "// comment mentioning java.io",
              wrap(
                  "// we never use java.io.File here", "$d.put(\"ok\", true); // nor System.exit")),
          Arguments.of(
              "block comment mentioning Thread",
              wrap("/* Thread and\n ProcessBuilder are banned */", "$d.put(\"ok\", true);")));
    }

    @ParameterizedTest(name = "accepts: {0}")
    @MethodSource("acceptedDrls")
    void accepts(String name, String drl) {
      DrlSanitizer.SanitizationResult result = sanitizer.sanitize("test.rule", drl);

      assertThat(result.getViolations()).as(name).isEmpty();
      assertThat(result.isAccepted()).isTrue();
    }
  }

  @Nested
  @DisplayName("normalize")
  class Normalize {

    @Test
    @DisplayName("removes // comments but keeps the newline")
    void removesLineComments() {
      assertThat(DrlSanitizer.normalize("a // x.y\nb")).isEqualTo("a \nb");
    }

    @Test
    @DisplayName("removes block comments, keeping their newlines")
    void removesBlockComments() {
      assertThat(DrlSanitizer.normalize("a /* one\ntwo\nthree */ b")).isEqualTo("a \n\n  b");
    }

    @Test
    @DisplayName("blanks string and char literal contents but keeps the quotes")
    void blanksStringContents() {
      assertThat(DrlSanitizer.normalize("put(\"k\", \"System.exit\")"))
          .isEqualTo("put(\"\", \"\")");
      assertThat(DrlSanitizer.normalize("c = 'x';")).isEqualTo("c = '';");
    }

    @Test
    @DisplayName("comment markers inside a string are not comments")
    void commentMarkersInsideString() {
      assertThat(DrlSanitizer.normalize("s = \"http://x/*y\"; z")).isEqualTo("s = \"\"; z");
    }

    @Test
    @DisplayName("an escaped quote does not end the string")
    void escapedQuote() {
      assertThat(DrlSanitizer.normalize("s = \"a\\\" System.exit\"; t")).isEqualTo("s = \"\"; t");
    }

    @Test
    @DisplayName("collapses whitespace around dots, including newlines")
    void collapsesWhitespaceAroundDot() {
      assertThat(DrlSanitizer.normalize("a . b")).isEqualTo("a.b");
      assertThat(DrlSanitizer.normalize("System\n    .exit(0)")).isEqualTo("System.exit(0)");
    }

    @Test
    @DisplayName("unterminated block comment / string do not throw")
    void unterminatedConstructs() {
      assertThat(DrlSanitizer.normalize("a /* never closed")).isEqualTo("a  ");
      assertThat(DrlSanitizer.normalize("a \"never closed")).isEqualTo("a \"\"");
      assertThat(DrlSanitizer.normalize("a \"ends with escape\\")).isEqualTo("a \"\"");
      assertThat(DrlSanitizer.normalize("")).isEmpty();
    }

    @Test
    @DisplayName("a string literal stops at end of line")
    void stringStopsAtNewline() {
      assertThat(DrlSanitizer.normalize("a \"open\nSystem.exit(0)"))
          .isEqualTo("a \"\"\nSystem.exit(0)");
    }
  }

  @Nested
  @DisplayName("Regressions found in review (2026-11-18)")
  class ReviewRegressions {

    private final DrlSanitizer sanitizer = new DrlSanitizer();

    @Test
    @DisplayName("unicode escapes are rejected (they can smuggle code out of a string)")
    void unicodeEscapeRejected() {
      String drl =
          "package x\nimport java.util.Map\nrule \"r\" when $d : Map() then\n"
              + "  String s = \"\\u0022; java.lang.System.setProperty(\\u0022pwned\\u0022,"
              + " \\u0022yes\\u0022); //\";\nend";
      var result = sanitizer.sanitize("x.unicode", drl);
      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("Unicode escapes"));
    }

    @Test
    @DisplayName("an unterminated quote does not merge the next line (import still checked)")
    void unterminatedQuoteDoesNotHideImport() {
      String drl = "package x\n// '\nx 'open\nimport com.evil.Gadget\nrule \"r\" when then end";
      var result = sanitizer.sanitize("x.quote", drl);
      assertThat(result.isAccepted()).isFalse();
      assertThat(result.getViolations()).anyMatch(v -> v.contains("com.evil.Gadget"));
    }
  }
}
