package com.company.drools.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the Drools Rule Engine API Allows all origins by default but configurable
 * for production environments
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  @Value("${drools.cors.allowed-origins:*}")
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
    List<String> origins = parseCommaSeparatedValues(allowedOrigins);
    List<String> methods = parseCommaSeparatedValues(allowedMethods);
    List<String> headers = parseCommaSeparatedValues(allowedHeaders);

    registry
        .addMapping("/**")
        .allowedOriginPatterns(origins.toArray(new String[0]))
        .allowedMethods(methods.toArray(new String[0]))
        .allowedHeaders(headers.toArray(new String[0]))
        .allowCredentials(allowCredentials)
        .maxAge(maxAge);
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    List<String> origins = parseCommaSeparatedValues(allowedOrigins);
    List<String> methods = parseCommaSeparatedValues(allowedMethods);
    List<String> headers = parseCommaSeparatedValues(allowedHeaders);

    configuration.setAllowedOriginPatterns(origins);
    configuration.setAllowedMethods(methods);
    configuration.setAllowedHeaders(headers);
    configuration.setAllowCredentials(allowCredentials);
    configuration.setMaxAge(maxAge);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private List<String> parseCommaSeparatedValues(String value) {
    if (value == null || value.trim().isEmpty()) {
      return Arrays.asList("*");
    }
    return Arrays.asList(value.split("\\s*,\\s*"));
  }
}
