package com.company.drools;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests providing Spring Boot context and common utilities. All
 * integration test classes should extend this class.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {

  /**
   * Load sample rules from the sample-rules directory for testing. Useful for integration tests
   * that need real Drools rules.
   *
   * @return List of Rule objects loaded from sample-rules directory
   * @throws IOException if rule files cannot be read
   */
  protected List<Rule> loadSampleRules() throws IOException {
    List<Rule> rules = new ArrayList<>();
    Path sampleRulesDir = Paths.get("sample-rules");

    if (!Files.exists(sampleRulesDir)) {
      throw new IOException("Sample rules directory not found: " + sampleRulesDir);
    }

    try (Stream<Path> paths = Files.walk(sampleRulesDir)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".drl"))
          .forEach(
              path -> {
                try {
                  String content = Files.readString(path);
                  String ruleId = extractRuleIdFromPath(path);
                  rules.add(new Rule(ruleId, content, RuleMetadata.createNew()));
                } catch (IOException e) {
                  throw new RuntimeException("Failed to load rule: " + path, e);
                }
              });
    }

    return rules;
  }

  /**
   * Extract rule ID from file path. Example: sample-rules/pricing/discount/simple.drl ->
   * pricing.discount.simple
   */
  private String extractRuleIdFromPath(Path path) {
    Path sampleRulesDir = Paths.get("sample-rules");
    Path relativePath = sampleRulesDir.relativize(path);
    String pathStr = relativePath.toString().replace(".drl", "").replace("/", ".");
    return pathStr.replace("\\", "."); // Handle Windows paths
  }

  /** Load a single rule from the sample-rules directory. */
  protected Rule loadSampleRule(String ruleId) throws IOException {
    String path = "sample-rules/" + ruleId.replace(".", "/") + ".drl";
    String content = Files.readString(Paths.get(path));
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }
}
