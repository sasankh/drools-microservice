package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("S3Config")
class S3ConfigTest {

  private S3Config s3Config;

  @BeforeEach
  void setUp() throws Exception {
    s3Config = new S3Config();
    setField(s3Config, "region", "us-east-1");
    setField(s3Config, "endpoint", "http://localhost:4566");
    setField(s3Config, "accessKeyId", "test-key");
    setField(s3Config, "secretAccessKey", "test-secret");
    setField(s3Config, "maxConnections", 50);
    setField(s3Config, "maxIdleTimeSeconds", 60);
    setField(s3Config, "connectionTimeoutSeconds", 10);
    setField(s3Config, "socketTimeoutSeconds", 60);
  }

  @Nested
  @DisplayName("Getters and Setters")
  class GettersAndSetters {

    @Test
    @DisplayName("getRegion returns configured region")
    void testGetRegion() {
      assertThat(s3Config.getRegion()).isEqualTo("us-east-1");
    }

    @Test
    @DisplayName("setRegion updates region")
    void testSetRegion() {
      s3Config.setRegion("eu-west-1");
      assertThat(s3Config.getRegion()).isEqualTo("eu-west-1");
    }

    @Test
    @DisplayName("getEndpoint returns configured endpoint")
    void testGetEndpoint() {
      assertThat(s3Config.getEndpoint()).isEqualTo("http://localhost:4566");
    }

    @Test
    @DisplayName("setEndpoint updates endpoint")
    void testSetEndpoint() {
      s3Config.setEndpoint("http://newhost:4566");
      assertThat(s3Config.getEndpoint()).isEqualTo("http://newhost:4566");
    }

    @Test
    @DisplayName("setAccessKeyId does not throw")
    void testSetAccessKeyId() {
      s3Config.setAccessKeyId("new-key");
      // No public getter — credential getters removed to prevent secret exposure
    }

    @Test
    @DisplayName("setSecretAccessKey does not throw")
    void testSetSecretAccessKey() {
      s3Config.setSecretAccessKey("new-secret");
      // No public getter — credential getters removed to prevent secret exposure
    }
  }

  @Nested
  @DisplayName("S3 Client Creation")
  class S3ClientCreation {

    @Test
    @DisplayName("s3Client creates client with custom endpoint")
    void testS3ClientWithEndpoint() {
      // This tests the full bean creation path including connection pooling
      var client = s3Config.s3Client();
      assertThat(client).isNotNull();
      client.close();
    }

    @Test
    @DisplayName("s3Client creates client without endpoint (default credentials)")
    void testS3ClientWithoutEndpoint() throws Exception {
      setField(s3Config, "endpoint", "");
      setField(s3Config, "accessKeyId", "");
      setField(s3Config, "secretAccessKey", "");

      // This will use default credentials provider
      var client = s3Config.s3Client();
      assertThat(client).isNotNull();
      client.close();
    }

    @Test
    @DisplayName("s3Client with key but no secret uses default credentials")
    void testS3ClientKeyButNoSecret() throws Exception {
      setField(s3Config, "endpoint", "http://localhost:4566");
      setField(s3Config, "accessKeyId", "test-key");
      setField(s3Config, "secretAccessKey", "");

      var client = s3Config.s3Client();
      assertThat(client).isNotNull();
      client.close();
    }

    @Test
    @DisplayName("s3Client with secret but no key uses default credentials")
    void testS3ClientSecretButNoKey() throws Exception {
      setField(s3Config, "endpoint", "http://localhost:4566");
      setField(s3Config, "accessKeyId", "");
      setField(s3Config, "secretAccessKey", "test-secret");

      var client = s3Config.s3Client();
      assertThat(client).isNotNull();
      client.close();
    }
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
