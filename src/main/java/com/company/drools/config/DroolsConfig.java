package com.company.drools.config;

import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.KieRepository;
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

  @Bean
  public KieContainer kieContainer(KieServices kieServices) {
    log.info("Initializing Drools KieContainer...");
    
    KieRepository kieRepository = kieServices.getRepository();
    
    // For now, create an empty KieContainer
    // This will be enhanced when we add rule loading functionality
    KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
    
    KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
    kieBuilder.buildAll();
    
    KieModule kieModule = kieBuilder.getKieModule();
    KieContainer kieContainer = kieServices.newKieContainer(kieModule.getReleaseId());
    
    log.info("Drools KieContainer initialized successfully");
    return kieContainer;
  }
}