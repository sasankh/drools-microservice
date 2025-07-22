package com.company.drools.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "aws")
public class S3Config {

  private static final Logger log = LoggerFactory.getLogger(S3Config.class);

  @Value("${aws.region:us-east-1}")
  private String region;

  @Value("${aws.endpoint:}")
  private String endpoint;

  @Value("${aws.access-key-id:}")
  private String accessKeyId;

  @Value("${aws.secret-access-key:}")
  private String secretAccessKey;

  @Bean
  public S3Client s3Client() {
    log.info("Configuring S3 client for region: {}", region);

    var clientBuilder = S3Client.builder()
        .region(Region.of(region))
        .credentialsProvider(createCredentialsProvider())
        .overrideConfiguration(builder -> builder.retryPolicy(createRetryPolicy()));

    // Configure endpoint for LocalStack or custom S3-compatible services
    if (StringUtils.hasText(endpoint)) {
      log.info("Using custom S3 endpoint: {}", endpoint);
      clientBuilder.endpointOverride(URI.create(endpoint))
          .forcePathStyle(true); // Required for LocalStack
    }

    S3Client s3Client = clientBuilder.build();
    log.info("S3 client configured successfully");
    return s3Client;
  }

  private AwsCredentialsProvider createCredentialsProvider() {
    // Use explicit credentials if provided (for LocalStack or testing)
    if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
      log.debug("Using static credentials provider");
      return StaticCredentialsProvider.create(
          AwsBasicCredentials.create(accessKeyId, secretAccessKey)
      );
    }

    // Use default credentials chain for production (IAM roles, etc.)
    log.debug("Using default credentials provider chain");
    return DefaultCredentialsProvider.create();
  }

  private RetryPolicy createRetryPolicy() {
    return RetryPolicy.builder()
        .numRetries(3)
        .backoffStrategy(BackoffStrategy.defaultStrategy())
        .throttlingBackoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
        .build();
  }

  // Configuration properties for external access
  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getAccessKeyId() {
    return accessKeyId;
  }

  public void setAccessKeyId(String accessKeyId) {
    this.accessKeyId = accessKeyId;
  }

  public String getSecretAccessKey() {
    return secretAccessKey;
  }

  public void setSecretAccessKey(String secretAccessKey) {
    this.secretAccessKey = secretAccessKey;
  }
}