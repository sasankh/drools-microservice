package com.company.drools.config;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the Drools Rule Engine API. Uses restrictive origins by default; override
 * via DROOLS_CORS_ALLOWED_ORIGINS for development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

  @Value("${drools.cors.allowed-origins:}")
  private String allowedOrigins;

  @Value("${drools.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
  private String allowedMethods;

  @Value("${drools.cors.allowed-headers:*}")
  private String allowedHeaders;

  @Value("${drools.cors.allow-credentials:false}")
  private boolean allowCredentials;

  @Value("${drools.cors.max-age:3600}")
  private long maxAge;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    warnIfWildcardOrigins();

    List<String> origins = parseCommaSeparatedValues(allowedOrigins);
    List<String> methods = parseCommaSeparatedValues(allowedMethods);
    List<String> headers = parseCommaSeparatedValues(allowedHeaders);

    if (origins.isEmpty()) {
      return;
    }

    registry
        .addMapping("/**")
        .allowedOriginPatterns(origins.toArray(new String[0]))
        .allowedMethods(methods.toArray(new String[0]))
        .allowedHeaders(headers.toArray(new String[0]))
        .allowCredentials(allowCredentials)
        .maxAge(maxAge);
  }

  private List<String> parseCommaSeparatedValues(String value) {
    if (value == null || value.trim().isEmpty()) {
      return List.of();
    }
    return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private void warnIfWildcardOrigins() {
    List<String> origins = parseCommaSeparatedValues(allowedOrigins);
    if (origins.contains("*")) {
      log.warn(
          "CORS allowed-origins is set to wildcard (*). "
              + "Set DROOLS_CORS_ALLOWED_ORIGINS to restrict origins in production.");
    }
    if (origins.isEmpty()) {
      log.info("CORS allowed-origins is empty — no cross-origin requests will be allowed");
    }
  }
}
