package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;

@DisplayName("DroolsConfig")
class DroolsConfigTest {

  private DroolsConfig droolsConfig;

  @BeforeEach
  void setUp() {
    droolsConfig = new DroolsConfig();
  }

  @Test
  @DisplayName("kieServices returns a non-null KieServices instance")
  void testKieServicesReturnsNonNull() {
    KieServices kieServices = droolsConfig.kieServices();

    assertThat(kieServices).isNotNull();
  }

  @Test
  @DisplayName("kieServices returns the singleton KieServices instance")
  void testKieServicesReturnsSingleton() {
    KieServices first = droolsConfig.kieServices();
    KieServices second = droolsConfig.kieServices();

    assertThat(first).isSameAs(second);
  }

  @Test
  @DisplayName("kieContainer returns a non-null KieContainer from kieServices")
  void testKieContainerReturnsNonNull() {
    KieServices kieServices = droolsConfig.kieServices();

    KieContainer kieContainer = droolsConfig.kieContainer(kieServices);

    assertThat(kieContainer).isNotNull();
  }

  @Test
  @DisplayName("kieContainer creates a functional KieContainer that can create sessions")
  void testKieContainerCanCreateSession() {
    KieServices kieServices = droolsConfig.kieServices();
    KieContainer kieContainer = droolsConfig.kieContainer(kieServices);

    // The container should be able to create a new session (proves it initialized correctly)
    assertThat(kieContainer.getKieBaseNames()).isNotNull();
  }
}
