package com.company.drools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class Application {

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public static void main(String[] args) {
    log.info("Starting Drools Rule Engine Microservice...");
    SpringApplication.run(Application.class, args);
    log.info("Drools Rule Engine Microservice started successfully");
  }
}
