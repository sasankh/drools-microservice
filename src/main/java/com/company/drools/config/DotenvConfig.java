package com.company.drools.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Configuration class to load .env file into Spring Environment. This allows environment variables
 * to be loaded from a .env file during application startup.
 */
public class DotenvConfig implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    try {
      // Load .env file if it exists
      Dotenv dotenv = Dotenv.configure().directory(".").filename(".env").ignoreIfMissing().load();

      // Convert to Map for Spring PropertySource
      Map<String, Object> dotenvMap = new HashMap<>();
      dotenv.entries().forEach(entry -> dotenvMap.put(entry.getKey(), entry.getValue()));

      // Add to Spring Environment with lowest priority (real env vars and system props override)
      ConfigurableEnvironment environment = applicationContext.getEnvironment();
      environment.getPropertySources().addLast(new MapPropertySource("dotenv", dotenvMap));

    } catch (Exception e) {
      // Silently ignore .env loading errors - it's optional
      System.out.println("Note: .env file not found or could not be loaded (this is optional)");
    }
  }
}
