package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
      // No public getter — credential getters removed to prevent secret exposure
      assertDoesNotThrow(() -> s3Config.setAccessKeyId("new-key"));
    }

    @Test
    @DisplayName("setSecretAccessKey does not throw")
    void testSetSecretAccessKey() {
      // No public getter — credential getters removed to prevent secret exposure
      assertDoesNotThrow(() -> s3Config.setSecretAccessKey("new-secret"));
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

    static Stream<Arguments> credentialCombinations() {
      return Stream.of(
          Arguments.of("", "", ""),
          Arguments.of("http://localhost:4566", "test-key", ""),
          Arguments.of("http://localhost:4566", "", "test-secret"));
    }

    @ParameterizedTest(name = "s3Client creates client with endpoint=[{0}]")
    @MethodSource("credentialCombinations")
    void testS3ClientWithCredentialCombinations(
        String endpoint, String accessKeyId, String secretAccessKey) throws Exception {
      setField(s3Config, "endpoint", endpoint);
      setField(s3Config, "accessKeyId", accessKeyId);
      setField(s3Config, "secretAccessKey", secretAccessKey);

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
