package com.company.drools.testutil;

import com.company.drools.config.ValidationConfig;
import java.lang.reflect.Field;

/**
 * Helper utility to create ValidationConfig instances for testing.
 * Since ValidationConfig uses @Value injection, we need to manually set fields via reflection.
 */
public class ValidationConfigTestHelper {

  /**
   * Create a ValidationConfig with default test values.
   * Uses reflection to set private fields since they're normally set by Spring @Value injection.
   */
  public static ValidationConfig createTestValidationConfig() {
    ValidationConfig config = new ValidationConfig();

    try {
      setField(config, "ruleIdMaxLength", 255);
      setField(config, "dataMaxFields", 50);
      setField(config, "dataMaxStringLength", 500);
      setField(config, "dataMaxNumberValue", 1000000L);
      setField(config, "requestMaxSizeBytes", 1048576L);
    } catch (Exception e) {
      throw new RuntimeException("Failed to configure ValidationConfig for tests", e);
    }

    return config;
  }

  private static void setField(Object target, String fieldName, Object value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
