package com.company.drools.core.engine;

import com.company.drools.core.model.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RuleCompiler {

  // Shared artifact identity for the in-memory rules KieModule.
  // DroolsConfig must use the same groupId/artifactId when it creates the initial KieContainer
  // so subsequent kieContainer.updateToVersion(...) calls can resolve newly-built modules from
  // the KieRepository — updateToVersion requires matching groupId:artifactId.
  public static final String GROUP_ID = "com.company.drools";
  public static final String ARTIFACT_ID = "rules-runtime";
  public static final String INITIAL_VERSION = "1.0.0";

  private static final Logger log = LoggerFactory.getLogger(RuleCompiler.class);

  private final KieServices kieServices;
  private final DrlSanitizer drlSanitizer;
  private final AtomicLong versionCounter = new AtomicLong(0);

  public RuleCompiler(KieServices kieServices, DrlSanitizer drlSanitizer) {
    this.kieServices = kieServices;
    this.drlSanitizer = drlSanitizer;
  }

  public CompilationResult compileRules(List<Rule> rules) {
    log.debug("Compiling {} rules", rules.size());

    try {
      List<String> allViolations = new ArrayList<>();
      for (Rule rule : rules) {
        DrlSanitizer.SanitizationResult result =
            drlSanitizer.sanitize(rule.getRuleId(), rule.getContent());
        if (!result.isAccepted()) {
          allViolations.add(rule.getRuleId() + ": " + result.getViolations());
        }
      }
      if (!allViolations.isEmpty()) {
        log.error("DRL sanitization rejected {} rule(s): {}", allViolations.size(), allViolations);
        return CompilationResult.failure(
            "DRL security violations: " + String.join("; ", allViolations));
      }

      long versionNumber = versionCounter.incrementAndGet();
      ReleaseId releaseId =
          kieServices.newReleaseId(GROUP_ID, ARTIFACT_ID, "1.0." + versionNumber);

      KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
      kieFileSystem.generateAndWritePomXML(releaseId);

      for (Rule rule : rules) {
        String resourcePath =
            "src/main/resources/rules/" + rule.getRuleId().replace(".", "/") + ".drl";
        kieFileSystem.write(resourcePath, rule.getContent());
        log.debug("Added rule {} to KieFileSystem at path {}", rule.getRuleId(), resourcePath);
      }

      KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
      kieBuilder.buildAll();

      Results results = kieBuilder.getResults();
      if (results.hasMessages(Message.Level.ERROR)) {
        log.error("Rule compilation failed with errors:");
        for (Message message : results.getMessages(Message.Level.ERROR)) {
          log.error("  - {}", message.getText());
        }
        return CompilationResult.failure(
            "Compilation errors: " + results.getMessages(Message.Level.ERROR));
      }

      if (results.hasMessages(Message.Level.WARNING)) {
        log.warn("Rule compilation completed with warnings:");
        for (Message message : results.getMessages(Message.Level.WARNING)) {
          log.warn("  - {}", message.getText());
        }
      }

      // KieBuilder auto-registers the resulting KieModule in the KieRepository under releaseId.
      // The engine service will call kieContainer.updateToVersion(releaseId) to swap the running
      // KieBase to the new module without disrupting the long-lived KieContainer.
      log.info(
          "Successfully compiled {} rules at release {}", rules.size(), releaseId.getVersion());
      return CompilationResult.success(releaseId);

    } catch (Exception e) {
      log.error("Failed to compile rules", e);
      return CompilationResult.failure("Compilation exception: " + e.getMessage());
    }
  }

  public static class CompilationResult {
    private final boolean success;
    private final ReleaseId releaseId;
    private final String errorMessage;

    private CompilationResult(boolean success, ReleaseId releaseId, String errorMessage) {
      this.success = success;
      this.releaseId = releaseId;
      this.errorMessage = errorMessage;
    }

    public static CompilationResult success(ReleaseId releaseId) {
      return new CompilationResult(true, releaseId, null);
    }

    public static CompilationResult failure(String errorMessage) {
      return new CompilationResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
      return success;
    }

    public ReleaseId getReleaseId() {
      return releaseId;
    }

    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
