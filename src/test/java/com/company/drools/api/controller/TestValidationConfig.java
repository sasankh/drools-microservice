package com.company.drools.api.controller;

import com.company.drools.config.ValidationConfig;
import com.company.drools.testutil.ValidationConfigTestHelper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;
import org.springframework.context.ApplicationContext;

/**
 * Test configuration to properly wire up validators with Spring beans.
 * This ensures custom validators like RuleIdValidator can inject Spring beans like ValidationConfig.
 */
@TestConfiguration
public class TestValidationConfig {

  /**
   * Create a ValidationConfig bean with test values.
   * This bean will be injected into custom validators like RuleIdValidator.
   */
  @Bean
  @Primary
  public ValidationConfig validationConfig() {
    return ValidationConfigTestHelper.createTestValidationConfig();
  }

  /**
   * Provide a simple MeterRegistry for tests.
   * Avoids complex mocking of Timer/Counter interactions.
   */
  @Bean
  @Primary
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  /**
   * Configure the validator factory to use Spring's application context for bean injection.
   * This allows @Autowired fields in ConstraintValidators to work properly.
   */
  @Bean
  @Primary
  public Validator validator(ApplicationContext applicationContext) {
    LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
    validatorFactory.setApplicationContext(applicationContext);
    // Use SpringConstraintValidatorFactory to enable @Autowired in validators
    validatorFactory.setConstraintValidatorFactory(
        new SpringConstraintValidatorFactory(applicationContext.getAutowireCapableBeanFactory()));
    return validatorFactory;
  }
}
