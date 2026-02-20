package com.company.drools.testutil;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for creating test data and rules. Provides helper methods commonly needed across
 * test classes.
 */
public class RuleTestUtils {

  /**
   * Create a simple valid Drools rule for testing. Useful for unit tests that don't need complex
   * rule logic.
   *
   * @param ruleId The rule identifier
   * @return Rule object with simple valid DRL content
   */
  public static Rule createSimpleRule(String ruleId) {
    String packageName = getPackageNameFromRuleId(ruleId);
    String content =
        String.format(
            """
            package %s

            import java.util.Map

            rule "%s"
            when
                $data : Map()
            then
                $data.put("executed", true);
            end
            """,
            packageName, ruleId);

    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  /** Create an invalid rule (missing package statement) for testing error handling. */
  public static Rule createInvalidRule(String ruleId) {
    String content =
        String.format(
            """
            // Missing package statement - should cause compilation error
            rule "%s"
            when
                $data : Map()
            then
            end
            """,
            ruleId);

    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  /**
   * Load a rule from a file path for testing.
   *
   * @param path Path to the .drl file
   * @return Rule object loaded from file
   * @throws IOException if file cannot be read
   */
  public static Rule loadRuleFromFile(String path) throws IOException {
    String content = Files.readString(Paths.get(path));
    String ruleId = extractRuleIdFromPath(path);
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  /**
   * Create test data map from key-value pairs. Example: createTestData("amount", 100.0,
   * "customerType", "VIP")
   *
   * @param keyValuePairs Alternating keys and values
   * @return Map containing the test data
   */
  public static Map<String, Object> createTestData(Object... keyValuePairs) {
    if (keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException("Must provide key-value pairs");
    }

    Map<String, Object> data = new HashMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      data.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return data;
  }

  /**
   * Extract rule ID from file path. Example: sample-rules/pricing/discount/simple.drl ->
   * pricing.discount.simple
   */
  private static String extractRuleIdFromPath(String path) {
    Path p = Paths.get(path);
    String filename = p.getFileName().toString().replace(".drl", "");
    Path parent = p.getParent();

    if (parent != null && parent.toString().contains("sample-rules")) {
      Path sampleRulesDir = Paths.get("sample-rules");
      Path relativePath = sampleRulesDir.relativize(p.getParent());
      return relativePath.toString().replace("/", ".") + "." + filename;
    }

    return filename;
  }

  /**
   * Get package name from rule ID. Example: pricing.discount.simple ->
   * com.company.rules.pricing.discount
   */
  private static String getPackageNameFromRuleId(String ruleId) {
    int lastDot = ruleId.lastIndexOf('.');
    if (lastDot > 0) {
      String path = ruleId.substring(0, lastDot);
      return "com.company.rules." + path;
    }
    return "com.company.rules";
  }
}
