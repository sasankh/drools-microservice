package com.company.drools.storage;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRuleStorage {

  private static final Logger log = LoggerFactory.getLogger(InMemoryRuleStorage.class);

  private final Map<String, String> ruleContents = new ConcurrentHashMap<>();

  public InMemoryRuleStorage() {
    // Add some sample rules for testing
    initializeSampleRules();
  }

  private void initializeSampleRules() {
    log.info("Initializing sample rules for testing");

    // Sample rule 1: Simple discount rule
    // NOTE: Uses Number/.doubleValue() pattern instead of (Double) direct cast.
    // Drools 10's executable model is strict about wrapper coercion: (Double)100 (Integer)
    // throws ClassCastException. Jackson parses JSON `100` as Integer by default. The
    // ((Number) ...).doubleValue() chain accepts both Integer and Double.
    String discountRule =
        """
        package com.company.rules.pricing.discount

        import java.util.Map
        import java.util.HashMap

        rule "Simple Discount Rule"
        when
            $data : Map(this["amount"] != null, ((Number)this["amount"]).doubleValue() > 50.0)
        then
            Map result = new HashMap();
            double amount = ((Number) $data.get("amount")).doubleValue();
            double discount = amount * 0.10; // 10% discount

            result.put("amount", amount);
            result.put("discount", discount);
            result.put("final_amount", amount - discount);
            result.put("applied_rule", "simple-discount");

            $data.put("result", result);
        end
        """;

    // Sample rule 2: VIP customer rule
    String vipRule =
        """
        package com.company.rules.pricing.discount

        import java.util.Map
        import java.util.HashMap

        rule "VIP Customer Rule"
        when
            $data : Map(
                this["customer_tier"] == "vip",
                this["amount"] != null,
                ((Number)this["amount"]).doubleValue() > 25.0
            )
        then
            Map result = new HashMap();
            double amount = ((Number) $data.get("amount")).doubleValue();
            double discount = amount * 0.20; // 20% discount for VIP

            result.put("amount", amount);
            result.put("discount", discount);
            result.put("final_amount", amount - discount);
            result.put("applied_rule", "vip-discount");
            result.put("customer_tier", "vip");

            $data.put("result", result);
        end
        """;

    ruleContents.put("pricing.discount.simple", discountRule);
    ruleContents.put("pricing.discount.vip", vipRule);

    log.info("Initialized {} sample rules", ruleContents.size());
  }

  public List<Rule> loadAllRules() {
    log.debug("Loading all rules from in-memory storage");

    List<Rule> rules = new ArrayList<>();

    for (Map.Entry<String, String> entry : ruleContents.entrySet()) {
      String ruleId = entry.getKey();
      String content = entry.getValue();
      RuleMetadata metadata = RuleMetadata.createNew();

      Rule rule = new Rule(ruleId, content, metadata);
      rules.add(rule);
    }

    log.debug("Loaded {} rules from storage", rules.size());
    return rules;
  }

  public Rule loadRule(String ruleId) {
    String content = ruleContents.get(ruleId);
    if (content == null) {
      return null;
    }

    RuleMetadata metadata = RuleMetadata.createNew();
    return new Rule(ruleId, content, metadata);
  }

  public boolean hasRule(String ruleId) {
    return ruleContents.containsKey(ruleId);
  }

  public void addRule(String ruleId, String content) {
    ruleContents.put(ruleId, content);
    log.info("Added rule: {}", ruleId);
  }

  public void removeRule(String ruleId) {
    ruleContents.remove(ruleId);
    log.info("Removed rule: {}", ruleId);
  }

  public int getRuleCount() {
    return ruleContents.size();
  }
}
