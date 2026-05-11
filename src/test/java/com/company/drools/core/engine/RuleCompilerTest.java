package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.testutil.RuleTestUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

@DisplayName("RuleCompiler")
class RuleCompilerTest {

  private RuleCompiler ruleCompiler;
  private KieServices kieServices;
  private DrlSanitizer drlSanitizer;

  @BeforeEach
  void setUp() {
    kieServices = KieServices.Factory.get();
    drlSanitizer = new DrlSanitizer();
    ruleCompiler = new RuleCompiler(kieServices, drlSanitizer);
  }

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("compiles a single valid rule successfully")
    void testCompileRules_SingleValidRule_Success() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getReleaseId()).isNotNull();
      assertThat(result.getReleaseId().getGroupId()).isEqualTo(RuleCompiler.GROUP_ID);
      assertThat(result.getReleaseId().getArtifactId()).isEqualTo(RuleCompiler.ARTIFACT_ID);
      assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("compiles multiple valid rules successfully")
    void testCompileRules_MultipleValidRules_Success() {
      Rule rule1 = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      Rule rule2 = RuleTestUtils.createSimpleRule("pricing.discount.vip");
      Rule rule3 = RuleTestUtils.createSimpleRule("validation.input.basic");

      RuleCompiler.CompilationResult result =
          ruleCompiler.compileRules(List.of(rule1, rule2, rule3));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getReleaseId()).isNotNull();
      assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("compiles a valid rule that may produce warnings but still succeeds")
    void testCompileRules_ValidRuleWithWarnings_SuccessWithWarnings() {
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
      Rule rule = new Rule("test.warning", content, RuleMetadata.createNew());

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getReleaseId()).isNotNull();
    }

    @Test
    @DisplayName("each successive compile assigns a bumped release version")
    void testCompileRules_AssignsBumpedReleaseId() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");

      RuleCompiler.CompilationResult first = ruleCompiler.compileRules(List.of(rule));
      RuleCompiler.CompilationResult second = ruleCompiler.compileRules(List.of(rule));
      RuleCompiler.CompilationResult third = ruleCompiler.compileRules(List.of(rule));

      assertThat(first.isSuccess()).isTrue();
      assertThat(second.isSuccess()).isTrue();
      assertThat(third.isSuccess()).isTrue();

      String firstVersion = first.getReleaseId().getVersion();
      String secondVersion = second.getReleaseId().getVersion();
      String thirdVersion = third.getReleaseId().getVersion();

      assertThat(firstVersion).isNotEqualTo(secondVersion);
      assertThat(secondVersion).isNotEqualTo(thirdVersion);

      // Versions are "1.0.<n>" — extract n and verify monotonic
      long n1 = Long.parseLong(firstVersion.substring(firstVersion.lastIndexOf('.') + 1));
      long n2 = Long.parseLong(secondVersion.substring(secondVersion.lastIndexOf('.') + 1));
      long n3 = Long.parseLong(thirdVersion.substring(thirdVersion.lastIndexOf('.') + 1));
      assertThat(n2).isGreaterThan(n1);
      assertThat(n3).isGreaterThan(n2);
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("returns failure for rule with invalid syntax")
    void testCompileRules_InvalidSyntax_ReturnsFailure() {
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
      Rule rule = new Rule("test.badsyntax", content, RuleMetadata.createNew());

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).isNotNull();
      assertThat(result.getReleaseId()).isNull();
    }

    @Test
    @DisplayName("returns failure for rule with missing package statement")
    void testCompileRules_MissingPackage_ReturnsFailure() {
      Rule rule = RuleTestUtils.createInvalidRule("test.nopackage");

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).isNotNull();
      assertThat(result.getReleaseId()).isNull();
    }

    @Test
    @DisplayName("returns failure for rules with duplicate names in same package")
    void testCompileRules_DuplicateRuleNames_ReturnsFailure() {
      String content1 =
          """
          package com.company.rules.test

          import java.util.Map

          rule "duplicate-name"
          when
              $data : Map()
          then
              $data.put("first", true);
          end
          """;
      String content2 =
          """
          package com.company.rules.test

          import java.util.Map

          rule "duplicate-name"
          when
              $data : Map()
          then
              $data.put("second", true);
          end
          """;
      Rule rule1 = new Rule("test.dup1", content1, RuleMetadata.createNew());
      Rule rule2 = new Rule("test.dup2", content2, RuleMetadata.createNew());

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule1, rule2));

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).isNotNull();
    }

    @Test
    @DisplayName("returns failure for empty content")
    void testCompileRules_EmptyContent_ReturnsFailure() {
      Rule rule = new Rule("test.empty", " ", RuleMetadata.createNew());

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule));

      assertThat(result).isNotNull();
    }
  }

  @Nested
  @DisplayName("KieRepository Integration")
  class KieRepositoryIntegration {

    @Test
    @DisplayName("registers a KieModule that can be resolved into a working KieContainer")
    void testCompileRules_RegistersResolvableModule() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");

      RuleCompiler.CompilationResult result = ruleCompiler.compileRules(List.of(rule));

      assertThat(result.isSuccess()).isTrue();
      ReleaseId releaseId = result.getReleaseId();

      // The registered module should be resolvable via KieServices.newKieContainer.
      KieContainer kieContainer = kieServices.newKieContainer(releaseId);
      assertThat(kieContainer).isNotNull();

      KieSession session = kieContainer.newKieSession();
      assertThat(session).isNotNull();
      session.dispose();

      kieContainer.dispose();
    }
  }
}
