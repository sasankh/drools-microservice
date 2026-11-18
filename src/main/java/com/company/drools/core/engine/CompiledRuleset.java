package com.company.drools.core.engine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.kie.api.KieBase;

/**
 * Immutable, compiled form of ONE rule ID (one DRL file): its own {@link KieBase} containing only
 * that file's rules (ADR-025). A request for {@code rule_id = X} opens a session on X's base only,
 * so unrelated rules can never fire.
 *
 * <p>Handles are never mutated. A refresh compiles a new handle and swaps it in the {@link
 * CompiledRulesetRegistry}; in-flight requests keep the reference they already took and finish on
 * that version. The JVM frees a retired handle once nothing references it (verified in the Phase 1
 * spike: flat heap/metaspace over 2,000 refreshes).
 *
 * @param ruleId the rule ID ({@code pricing.discount.simple})
 * @param contentDigest SHA-256 (hex) of the DRL source this base was compiled from
 * @param kieBase the compiled base; contains only this file's rules
 * @param compiledAt when it was compiled
 * @param ruleNames names of the DRL rules inside the file
 */
public record CompiledRuleset(
    String ruleId,
    String contentDigest,
    KieBase kieBase,
    Instant compiledAt,
    List<String> ruleNames) {

  public CompiledRuleset {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(contentDigest, "contentDigest");
    Objects.requireNonNull(kieBase, "kieBase");
    Objects.requireNonNull(compiledAt, "compiledAt");
    ruleNames = List.copyOf(ruleNames);
  }

  /** First 12 hex chars of the digest, for logs. */
  public String shortDigest() {
    return contentDigest.length() > 12 ? contentDigest.substring(0, 12) : contentDigest;
  }
}
