package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.testutil.RuleTestUtils;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

@DisplayName("RuleCompiler")
class RuleCompilerTest {

  private RuleCompiler ruleCompiler;

  @BeforeEach
  void setUp() {
    ruleCompiler = new RuleCompiler(new DrlSanitizer());
  }

  private static Rule rule(String ruleId, String content) {
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  /** One DRL rule named {@code ruleName} that puts {@code key=true} into the input map. */
  private static String singleRuleDrl(String pkg, String ruleName, String key) {
    return String.format(
        """
        package %s

        import java.util.Map

        rule "%s"
        when
            $data : Map()
        then
            $data.put("%s", true);
        end
        """,
        pkg, ruleName, key);
  }

  private static Map<String, Object> fire(KieBase kieBase) {
    Map<String, Object> data = new HashMap<>();
    try (KieSession session = kieBase.newKieSession()) {
      session.insert(data);
      session.fireAllRules();
    }
    return data;
  }

  @Nested
  @DisplayName("compileRule — happy path")
  class HappyPath {

    @Test
    @DisplayName("compiles a single valid rule into its own executable KieBase")
    void testCompileRule_SingleValidRule_Success() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      Instant before = Instant.now();

      RuleCompiler.RuleCompilation result = ruleCompiler.compileRule(rule);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.ruleId()).isEqualTo("pricing.discount.simple");
      assertThat(result.errorMessage()).isNull();
      CompiledRuleset ruleset = result.ruleset();
      assertThat(ruleset.ruleId()).isEqualTo("pricing.discount.simple");
      assertThat(ruleset.kieBase()).isNotNull();
      assertThat(ruleset.compiledAt()).isAfterOrEqualTo(before);
      assertThat(ruleset.contentDigest()).isEqualTo(RuleCompiler.sha256(rule.getContent()));
      assertThat(ruleset.shortDigest())
          .hasSize(12)
          .isEqualTo(ruleset.contentDigest().substring(0, 12));
      assertThat(fire(ruleset.kieBase())).containsEntry("executed", true);
    }

    @Test
    @DisplayName("extracts the DRL rule names, sorted")
    void testCompileRule_ExtractsRuleNames() {
      String content =
          """
          package com.company.rules.multi

          import java.util.Map

          rule "zulu"
          when
              $data : Map()
          then
              $data.put("z", true);
          end

          rule "alpha"
          when
              $data : Map()
          then
              $data.put("a", true);
          end
          """;

      RuleCompiler.RuleCompilation result = ruleCompiler.compileRule(rule("multi.names", content));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.ruleset().ruleNames()).containsExactly("alpha", "zulu");
    }

    @Test
    @DisplayName("a file with no rules compiles to an empty base (warn-only)")
    void testCompileRule_NoRules_SuccessWithEmptyNames() {
      RuleCompiler.RuleCompilation result =
          ruleCompiler.compileRule(rule("empty.package", "package com.company.rules.empty\n"));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.ruleset().ruleNames()).isEmpty();
    }

    @Test
    @DisplayName("a valid rule that may produce builder warnings still succeeds")
    void testCompileRule_ValidRuleWithWarnings_Success() {
      String content =
          """
          package com.company.rules.test

          import java.util.Map

          rule "warning-rule"
          when
              $data : Map()
              $other : Map() from $data.values()
          then
              $data.put("executed", true);
          end
          """;

      RuleCompiler.RuleCompilation result = ruleCompiler.compileRule(rule("test.warning", content));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.ruleset().ruleNames()).containsExactly("warning-rule");
    }
  }

  @Nested
  @DisplayName("compileRule — error handling")
  class ErrorHandling {

    @Test
    @DisplayName("returns failure for rule with invalid syntax")
    void testCompileRule_InvalidSyntax_ReturnsFailure() {
      String content =
          """
          package com.company.rules.test

          import java.util.Map

          rule "bad-syntax"
          when
              $data : Map()
          then
              $data.put("executed", true)
          end
          """;

      RuleCompiler.RuleCompilation result =
          ruleCompiler.compileRule(rule("test.badsyntax", content));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.ruleId()).isEqualTo("test.badsyntax");
      assertThat(result.ruleset()).isNull();
      assertThat(result.errorMessage()).startsWith("Compilation errors:");
    }

    @Test
    @DisplayName("returns failure for rule with missing import / package")
    void testCompileRule_MissingPackage_ReturnsFailure() {
      RuleCompiler.RuleCompilation result =
          ruleCompiler.compileRule(RuleTestUtils.createInvalidRule("test.nopackage"));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.errorMessage()).isNotNull();
      assertThat(result.ruleset()).isNull();
    }

    @Test
    @DisplayName("rejects DRL that fails the sanitizer policy before compiling")
    void testCompileRule_SanitizerRejects_ReturnsFailure() {
      String content =
          """
          package com.company.rules.evil

          import java.util.Map

          rule "evil"
          when
              $data : Map()
          then
              System.exit(1);
          end
          """;

      RuleCompiler.RuleCompilation result = ruleCompiler.compileRule(rule("evil.rule", content));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.errorMessage()).startsWith("DRL security violations:");
    }

    @Test
    @DisplayName("an unexpected exception is returned as a failure, never thrown")
    void testCompileRule_UnexpectedException_ReturnsFailure() {
      DrlSanitizer throwing = mock(DrlSanitizer.class);
      when(throwing.sanitize(anyString(), anyString()))
          .thenThrow(new IllegalStateException("boom"));
      RuleCompiler compiler = new RuleCompiler(throwing);

      RuleCompiler.RuleCompilation result =
          compiler.compileRule(RuleTestUtils.createSimpleRule("throws.rule"));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.errorMessage()).isEqualTo("Compilation exception: boom");
    }

    @Test
    @DisplayName("duplicate rule name INSIDE one file still fails")
    void testCompileRule_DuplicateRuleNameInOneFile_ReturnsFailure() {
      String content =
          """
          package com.company.rules.test

          import java.util.Map

          rule "duplicate-name"
          when
              $data : Map()
          then
              $data.put("first", true);
          end

          rule "duplicate-name"
          when
              $data : Map()
          then
              $data.put("second", true);
          end
          """;

      RuleCompiler.RuleCompilation result = ruleCompiler.compileRule(rule("test.dup", content));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    @DisplayName("blank content is a failure or an empty base — never an exception")
    void testCompileRule_BlankContent_NoException() {
      RuleCompiler.RuleCompilation result = ruleCompiler.compileRule(rule("test.empty", " "));

      assertThat(result).isNotNull();
      assertThat(result.ruleId()).isEqualTo("test.empty");
    }
  }

  @Nested
  @DisplayName("compileRules — per-ID independence")
  class CompileRules {

    @Test
    @DisplayName("compiles multiple valid rule IDs, in input order")
    void testCompileRules_MultipleValidRules_Success() {
      Rule rule1 = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      Rule rule2 = RuleTestUtils.createSimpleRule("pricing.discount.vip");
      Rule rule3 = RuleTestUtils.createSimpleRule("validation.input.basic");

      Map<String, RuleCompiler.RuleCompilation> results =
          ruleCompiler.compileRules(List.of(rule1, rule2, rule3));

      assertThat(results)
          .containsOnlyKeys(
              "pricing.discount.simple", "pricing.discount.vip", "validation.input.basic");
      assertThat(results.keySet())
          .containsExactly(
              "pricing.discount.simple", "pricing.discount.vip", "validation.input.basic");
      assertThat(results.values()).allMatch(RuleCompiler.RuleCompilation::isSuccess);
      // Each ID gets its own base.
      assertThat(results.get("pricing.discount.simple").ruleset().kieBase())
          .isNotSameAs(results.get("pricing.discount.vip").ruleset().kieBase());
    }

    @Test
    @DisplayName("one bad rule ID does not affect the good ones")
    void testCompileRules_OneBadOneGood_Independent() {
      Rule good = RuleTestUtils.createSimpleRule("good.rule");
      Rule bad = RuleTestUtils.createInvalidRule("bad.rule");

      Map<String, RuleCompiler.RuleCompilation> results =
          ruleCompiler.compileRules(List.of(bad, good));

      assertThat(results.get("bad.rule").isSuccess()).isFalse();
      assertThat(results.get("bad.rule").errorMessage()).isNotNull();
      assertThat(results.get("good.rule").isSuccess()).isTrue();
      assertThat(fire(results.get("good.rule").ruleset().kieBase()))
          .containsEntry("executed", true);
    }

    @Test
    @DisplayName("the same DRL rule NAME in two DIFFERENT rule IDs compiles fine (separate bases)")
    void testCompileRules_SameRuleNameInDifferentIds_BothSucceed() {
      String content1 = singleRuleDrl("com.company.rules.test", "duplicate-name", "first");
      String content2 = singleRuleDrl("com.company.rules.test", "duplicate-name", "second");

      Map<String, RuleCompiler.RuleCompilation> results =
          ruleCompiler.compileRules(
              List.of(rule("test.dup1", content1), rule("test.dup2", content2)));

      assertThat(results.get("test.dup1").isSuccess()).isTrue();
      assertThat(results.get("test.dup2").isSuccess()).isTrue();
      // Each base only fires its own file's rule.
      assertThat(fire(results.get("test.dup1").ruleset().kieBase()))
          .containsEntry("first", true)
          .doesNotContainKey("second");
      assertThat(fire(results.get("test.dup2").ruleset().kieBase()))
          .containsEntry("second", true)
          .doesNotContainKey("first");
    }

    @Test
    @DisplayName("a duplicate rule ID in the input keeps the later definition (last wins)")
    void testCompileRules_DuplicateRuleId_LastWins() {
      Rule first = rule("dup.id", singleRuleDrl("com.company.rules.dup", "r", "first"));
      Rule second = rule("dup.id", singleRuleDrl("com.company.rules.dup", "r", "second"));

      Map<String, RuleCompiler.RuleCompilation> results =
          ruleCompiler.compileRules(List.of(first, second));

      assertThat(results).containsOnlyKeys("dup.id");
      CompiledRuleset ruleset = results.get("dup.id").ruleset();
      assertThat(ruleset.contentDigest()).isEqualTo(RuleCompiler.sha256(second.getContent()));
      assertThat(fire(ruleset.kieBase())).containsEntry("second", true).doesNotContainKey("first");
    }

    @Test
    @DisplayName("an empty input list yields no results")
    void testCompileRules_EmptyList_EmptyResult() {
      assertThat(ruleCompiler.compileRules(List.of())).isEmpty();
    }
  }

  @Nested
  @DisplayName("sha256")
  class Sha256 {

    @Test
    @DisplayName("is a stable 64-char lowercase hex digest")
    void testSha256_StableHex() {
      String digest = RuleCompiler.sha256("abc");

      assertThat(digest)
          .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
          .isEqualTo(RuleCompiler.sha256("abc"))
          .hasSize(64)
          .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("differs when content differs")
    void testSha256_DiffersOnChange() {
      assertThat(RuleCompiler.sha256("rule a")).isNotEqualTo(RuleCompiler.sha256("rule b"));
    }
  }

  @Nested
  @DisplayName("RuleCompilation")
  class RuleCompilationRecord {

    @Test
    @DisplayName("success/failure factories set isSuccess correctly")
    void testFactories() {
      KieBase kieBase = mock(KieBase.class);
      CompiledRuleset ruleset =
          new CompiledRuleset("x.y", "abc", kieBase, Instant.now(), List.of("r"));

      assertThat(RuleCompiler.RuleCompilation.success(ruleset).isSuccess()).isTrue();
      assertThat(RuleCompiler.RuleCompilation.success(ruleset).ruleId()).isEqualTo("x.y");
      assertThat(RuleCompiler.RuleCompilation.failure("x.y", "bad").isSuccess()).isFalse();
      // A digest shorter than 12 chars is returned as-is.
      assertThat(ruleset.shortDigest()).isEqualTo("abc");
    }
  }
}
