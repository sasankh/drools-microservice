package com.company.drools.core.engine;

import com.company.drools.core.model.Rule;
import java.util.List;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.kie.api.runtime.KieContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RuleCompiler {

  private static final Logger log = LoggerFactory.getLogger(RuleCompiler.class);

  private final KieServices kieServices;

  public RuleCompiler(KieServices kieServices) {
    this.kieServices = kieServices;
  }

  public CompilationResult compileRules(List<Rule> rules) {
    log.debug("Compiling {} rules", rules.size());

    try {
      KieFileSystem kieFileSystem = kieServices.newKieFileSystem();

      // Add each rule to the file system
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

      KieModule kieModule = kieBuilder.getKieModule();
      KieContainer kieContainer = kieServices.newKieContainer(kieModule.getReleaseId());

      log.info("Successfully compiled {} rules", rules.size());
      return CompilationResult.success(kieContainer);

    } catch (Exception e) {
      log.error("Failed to compile rules", e);
      return CompilationResult.failure("Compilation exception: " + e.getMessage());
    }
  }

  public static class CompilationResult {
    private final boolean success;
    private final KieContainer kieContainer;
    private final String errorMessage;

    private CompilationResult(boolean success, KieContainer kieContainer, String errorMessage) {
      this.success = success;
      this.kieContainer = kieContainer;
      this.errorMessage = errorMessage;
    }

    public static CompilationResult success(KieContainer kieContainer) {
      return new CompilationResult(true, kieContainer, null);
    }

    public static CompilationResult failure(String errorMessage) {
      return new CompilationResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
      return success;
    }

    public KieContainer getKieContainer() {
      return kieContainer;
    }

    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
