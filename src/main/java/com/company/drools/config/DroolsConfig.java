package com.company.drools.config;

import com.company.drools.core.engine.RuleCompiler;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieRepository;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DroolsConfig {

  private static final Logger log = LoggerFactory.getLogger(DroolsConfig.class);

  @Bean
  public KieServices kieServices() {
    return KieServices.Factory.get();
  }

  /**
   * The singleton KieRepository. Exposed as a bean so DroolsEngineService can call
   * removeKieModule(oldReleaseId) after a successful KieContainer.updateToVersion(...) — Drools
   * 10.2.0 does NOT auto-clean prior KieModules from the repository, so without explicit eviction
   * the repo accumulates compiled bytecode (one ProjectClassLoader per refresh) and leaks under
   * sustained hot reloads.
   */
  @Bean
  public KieRepository kieRepository(KieServices kieServices) {
    return kieServices.getRepository();
  }

  @Bean
  public KieContainer kieContainer(KieServices kieServices) {
    log.info("Initializing Drools KieContainer...");

    // Build an empty initial KieModule with the same groupId:artifactId that RuleCompiler emits.
    // This is required so DroolsEngineService can later call kieContainer.updateToVersion(...) —
    // updateToVersion only swaps to modules that share the current container's artifact identity.
    ReleaseId releaseId =
        kieServices.newReleaseId(
            RuleCompiler.GROUP_ID, RuleCompiler.ARTIFACT_ID, RuleCompiler.INITIAL_VERSION);

    KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
    kieFileSystem.generateAndWritePomXML(releaseId);

    KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
    kieBuilder.buildAll();

    KieContainer kieContainer = kieServices.newKieContainer(releaseId);

    log.info("Drools KieContainer initialized at release {}", releaseId.getVersion());
    return kieContainer;
  }
}
