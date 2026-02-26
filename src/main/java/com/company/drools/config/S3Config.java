package com.company.drools.config;

import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

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

  // Connection pool configuration
  @Value("${aws.s3.connection-pool.max-connections:50}")
  private int maxConnections;

  @Value("${aws.s3.connection-pool.max-idle-time:60}")
  private int maxIdleTimeSeconds;

  @Value("${aws.s3.connection-pool.connection-timeout:10}")
  private int connectionTimeoutSeconds;

  @Value("${aws.s3.connection-pool.socket-timeout:60}")
  private int socketTimeoutSeconds;

  @Bean
  public S3Client s3Client() {
    log.info("Configuring S3 client for region: {} with connection pooling", region);
    log.info(
        "Connection pool settings: max-connections={}, connection-timeout={}s, socket-timeout={}s, idle-time={}s",
        maxConnections,
        connectionTimeoutSeconds,
        socketTimeoutSeconds,
        maxIdleTimeSeconds);

    // Create HTTP client with connection pooling
    SdkHttpClient httpClient = createHttpClientWithPooling();

    var clientBuilder =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(createCredentialsProvider())
            .httpClient(httpClient)
            .overrideConfiguration(builder -> builder.retryPolicy(createRetryPolicy()));

    // Configure endpoint for LocalStack or custom S3-compatible services
    if (StringUtils.hasText(endpoint)) {
      validateEndpoint(endpoint);
      log.info("Using custom S3 endpoint: {}", endpoint);
      clientBuilder
          .endpointOverride(URI.create(endpoint))
          .forcePathStyle(true); // Required for LocalStack
    }

    S3Client s3Client = clientBuilder.build();
    log.info("S3 client configured successfully with connection pooling");
    return s3Client;
  }

  private AwsCredentialsProvider createCredentialsProvider() {
    // Use explicit credentials if provided (for LocalStack or testing)
    if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
      log.debug("Using static credentials provider");
      return StaticCredentialsProvider.create(
          AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }

    // Use default credentials chain for production (IAM roles, etc.)
    log.debug("Using default credentials provider chain");
    return DefaultCredentialsProvider.create();
  }

  private SdkHttpClient createHttpClientWithPooling() {
    log.debug("Creating HTTP client with connection pooling configuration");

    return ApacheHttpClient.builder()
        // Connection pool settings
        .maxConnections(maxConnections)
        // Timeouts
        .connectionTimeout(Duration.ofSeconds(connectionTimeoutSeconds))
        .socketTimeout(Duration.ofSeconds(socketTimeoutSeconds))
        // Keep-alive and idle connection management
        .connectionMaxIdleTime(Duration.ofSeconds(maxIdleTimeSeconds))
        // TCP keep-alive
        .tcpKeepAlive(true)
        // Expect-continue handshake
        .expectContinueEnabled(false)
        .build();
  }

  private RetryPolicy createRetryPolicy() {
    return RetryPolicy.builder()
        .numRetries(3)
        .backoffStrategy(BackoffStrategy.defaultStrategy())
        .throttlingBackoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
        .build();
  }

  private void validateEndpoint(String endpointUrl) {
    URI uri = URI.create(endpointUrl);
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
      throw new IllegalArgumentException(
          "Invalid S3 endpoint scheme: " + scheme + ". Only http and https are allowed.");
    }
    String host = uri.getHost();
    if (host == null) {
      throw new IllegalArgumentException("Invalid S3 endpoint: no host specified");
    }
    boolean allowed =
        host.equals("localhost")
            || host.equals("127.0.0.1")
            || host.endsWith(".amazonaws.com")
            || host.endsWith(".localstack.cloud");
    if (!allowed) {
      log.warn(
          "S3 endpoint host '{}' is not in the standard allowlist "
              + "(localhost, *.amazonaws.com, *.localstack.cloud). "
              + "Ensure this is intentional.",
          host);
    }
  }

  // Setters required by @ConfigurationProperties binding — no public getters for credentials
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

  public void setAccessKeyId(String accessKeyId) {
    this.accessKeyId = accessKeyId;
  }

  public void setSecretAccessKey(String secretAccessKey) {
    this.secretAccessKey = secretAccessKey;
  }
}
