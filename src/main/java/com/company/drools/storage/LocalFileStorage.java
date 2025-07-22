package com.company.drools.storage;

import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component("localFileStorage")
@org.springframework.context.annotation.Profile("file")
public class LocalFileStorage implements RuleStorage {

  private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

  @Value("${drools.local.rules-directory:src/main/resources/rules}")
  private String rulesDirectory;

  @Override
  public Optional<Rule> getRule(String ruleId) {
    log.debug("Loading rule: {}", ruleId);

    Path filePath = getRuleFilePath(ruleId);
    if (!Files.exists(filePath)) {
      log.warn("Rule file not found: {}", filePath);
      return Optional.empty();
    }

    try {
      String content = Files.readString(filePath);
      RuleMetadata metadata = createMetadata(filePath);
      Rule rule = new Rule(ruleId, content, metadata);

      log.debug("Successfully loaded rule: {} from {}", ruleId, filePath);
      return Optional.of(rule);
    } catch (IOException e) {
      log.error("Failed to read rule file: {}", filePath, e);
      return Optional.empty();
    }
  }

  @Override
  public List<Rule> getAllRules() {
    log.debug("Loading all rules from directory: {}", rulesDirectory);

    List<Rule> rules = new ArrayList<>();
    Path rulesPath = Paths.get(rulesDirectory);

    if (!Files.exists(rulesPath)) {
      log.warn("Rules directory does not exist: {}", rulesPath);
      return rules;
    }

    try (Stream<Path> paths = Files.walk(rulesPath)) {
      paths.filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".drl"))
          .forEach(
              path -> {
                String ruleId = pathToRuleId(path);
                getRule(ruleId).ifPresent(rules::add);
              });
    } catch (IOException e) {
      log.error("Failed to list rules in directory: {}", rulesPath, e);
    }

    log.info("Loaded {} rules from local file storage", rules.size());
    return rules;
  }

  @Override
  public void saveRule(Rule rule) {
    log.debug("Saving rule: {}", rule.getRuleId());

    Path filePath = getRuleFilePath(rule.getRuleId());
    try {
      Files.createDirectories(filePath.getParent());
      Files.writeString(filePath, rule.getContent());

      log.info("Successfully saved rule: {} to {}", rule.getRuleId(), filePath);
    } catch (IOException e) {
      log.error("Failed to save rule: {} to {}", rule.getRuleId(), filePath, e);
      throw new RuntimeException("Failed to save rule: " + rule.getRuleId(), e);
    }
  }

  @Override
  public void deleteRule(String ruleId) {
    log.debug("Deleting rule: {}", ruleId);

    Path filePath = getRuleFilePath(ruleId);
    if (!Files.exists(filePath)) {
      throw new RuleNotFoundException("Rule not found: " + ruleId);
    }

    try {
      Files.delete(filePath);
      log.info("Successfully deleted rule: {} from {}", ruleId, filePath);
    } catch (IOException e) {
      log.error("Failed to delete rule: {} from {}", ruleId, filePath, e);
      throw new RuntimeException("Failed to delete rule: " + ruleId, e);
    }
  }

  @Override
  public boolean ruleExists(String ruleId) {
    Path filePath = getRuleFilePath(ruleId);
    return Files.exists(filePath);
  }

  @Override
  public void refreshCache() {
    log.info("Refreshing local file storage cache (no-op for file storage)");
  }

  @Override
  public void refreshRule(String ruleId) {
    log.info("Refreshing rule: {} (no-op for file storage)", ruleId);
  }

  @Override
  public long getTotalRuleCount() {
    return getAllRules().size();
  }

  @Override
  public List<String> getRuleIds() {
    List<String> ruleIds = new ArrayList<>();
    Path rulesPath = Paths.get(rulesDirectory);

    if (!Files.exists(rulesPath)) {
      return ruleIds;
    }

    try (Stream<Path> paths = Files.walk(rulesPath)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".drl"))
          .forEach(path -> ruleIds.add(pathToRuleId(path)));
    } catch (IOException e) {
      log.error("Failed to list rule IDs from directory: {}", rulesPath, e);
    }

    return ruleIds;
  }

  private Path getRuleFilePath(String ruleId) {
    String relativePath = ruleId.replace(".", "/") + ".drl";
    return Paths.get(rulesDirectory, relativePath);
  }

  private String pathToRuleId(Path path) {
    Path rulesPath = Paths.get(rulesDirectory);
    Path relativePath = rulesPath.relativize(path);
    String pathStr = relativePath.toString();
    
    if (pathStr.endsWith(".drl")) {
      pathStr = pathStr.substring(0, pathStr.length() - 4);
    }
    
    return pathStr.replace("/", ".").replace("\\", ".");
  }

  private RuleMetadata createMetadata(Path filePath) {
    RuleMetadata metadata = RuleMetadata.createNew();
    try {
      Instant lastModified = Files.getLastModifiedTime(filePath).toInstant();
      return metadata.withLastModified(lastModified);
    } catch (IOException e) {
      log.warn("Failed to get last modified time for file: {}", filePath, e);
      return metadata;
    }
  }
}