package com.company.drools.core.engine;

import com.company.drools.core.model.Rule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.drools.kiesession.rulebase.KnowledgeBaseFactory;
import org.kie.api.io.ResourceType;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.builder.ResultSeverity;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compiles ONE rule ID (one DRL file) into its own {@link org.kie.api.KieBase} (ADR-025).
 *
 * <p>Uses {@code KnowledgeBuilder} + {@code KnowledgeBaseFactory}: no KieModule or KieRepository
 * involvement, so there is no module bookkeeping to leak. Chosen in the 2026-11-18 spike for the
 * lowest heap/metaspace and a ~3.6 ms single-rule rebuild at 1,000 resident IDs. {@code
 * KnowledgeBaseFactory} is an internal Drools API; it is confined to this class, and the tests
 * compile and execute all sample rules through it, so a Drools upgrade that changes it fails the
 * build here.
 */
@Component
public class RuleCompiler {

  private static final Logger log = LoggerFactory.getLogger(RuleCompiler.class);

  private final DrlSanitizer drlSanitizer;

  public RuleCompiler(DrlSanitizer drlSanitizer) {
    this.drlSanitizer = drlSanitizer;
  }

  /**
   * Policy-check and compile a single rule ID in isolation. Never throws: failures are returned.
   */
  public RuleCompilation compileRule(Rule rule) {
    String ruleId = rule.getRuleId();
    try {
      DrlSanitizer.SanitizationResult policy = drlSanitizer.sanitize(ruleId, rule.getContent());
      if (!policy.isAccepted()) {
        log.error("DRL policy check rejected rule {}: {}", ruleId, policy.getViolations());
        return RuleCompilation.failure(
            ruleId, "DRL security violations: " + policy.getViolations());
      }

      KnowledgeBuilder builder = KnowledgeBuilderFactory.newKnowledgeBuilder();
      builder.add(
          ResourceFactory.newByteArrayResource(rule.getContent().getBytes(StandardCharsets.UTF_8))
              .setSourcePath("rules/" + ruleId.replace('.', '/') + ".drl"),
          ResourceType.DRL);

      if (builder.hasErrors()) {
        log.error("Rule {} failed to compile: {}", ruleId, builder.getErrors());
        return RuleCompilation.failure(ruleId, "Compilation errors: " + builder.getErrors());
      }
      if (builder.hasResults(ResultSeverity.WARNING)) {
        log.warn(
            "Rule {} compiled with warnings: {}",
            ruleId,
            builder.getResults(ResultSeverity.WARNING));
      }

      InternalKnowledgeBase kieBase = KnowledgeBaseFactory.newKnowledgeBase();
      kieBase.addPackages(builder.getKnowledgePackages());

      List<String> ruleNames =
          kieBase.getKiePackages().stream()
              .flatMap(p -> p.getRules().stream())
              .map(org.kie.api.definition.rule.Rule::getName)
              .sorted()
              .toList();
      if (ruleNames.isEmpty()) {
        log.warn("Rule {} compiled but contains no rules", ruleId);
      }

      CompiledRuleset compiled =
          new CompiledRuleset(ruleId, sha256(rule.getContent()), kieBase, Instant.now(), ruleNames);
      log.debug(
          "Compiled rule {} ({} DRL rule(s), digest {})",
          ruleId,
          ruleNames.size(),
          compiled.shortDigest());
      return RuleCompilation.success(compiled);

    } catch (Exception e) {
      log.error("Failed to compile rule {}", ruleId, e);
      return RuleCompilation.failure(ruleId, "Compilation exception: " + e.getMessage());
    }
  }

  /**
   * Compile each rule ID independently. One failure never affects the others. A duplicate rule ID
   * in the input keeps the later definition (last wins) and logs a warning.
   *
   * @return per-ID results, in input order
   */
  public Map<String, RuleCompilation> compileRules(List<Rule> rules) {
    Map<String, Rule> unique = new LinkedHashMap<>();
    for (Rule rule : rules) {
      if (unique.put(rule.getRuleId(), rule) != null) {
        log.warn(
            "Duplicate rule ID '{}' in this load — the later definition overwrites the earlier one"
                + " (last-wins). Check the source for a duplicate.",
            rule.getRuleId());
      }
    }
    Map<String, RuleCompilation> results = new LinkedHashMap<>();
    unique.forEach((id, rule) -> results.put(id, compileRule(rule)));
    return results;
  }

  /** SHA-256 hex digest of DRL source text (UTF-8). */
  public static String sha256(String content) {
    return com.company.drools.common.ContentDigest.sha256(content);
  }

  /** Result of compiling one rule ID. */
  public record RuleCompilation(String ruleId, CompiledRuleset ruleset, String errorMessage) {

    public static RuleCompilation success(CompiledRuleset ruleset) {
      return new RuleCompilation(ruleset.ruleId(), ruleset, null);
    }

    public static RuleCompilation failure(String ruleId, String errorMessage) {
      return new RuleCompilation(ruleId, null, errorMessage);
    }

    public boolean isSuccess() {
      return ruleset != null;
    }
  }
}
