package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StorageConfig")
class StorageConfigTest {

  private StorageConfig storageConfig;

  @BeforeEach
  void setUp() {
    storageConfig = new StorageConfig();
  }

  @Test
  @DisplayName("default ruleSource is 'local'")
  void testDefaultRuleSource() {
    assertThat(storageConfig.getRuleSource()).isEqualTo("local");
  }

  @Test
  @DisplayName("default s3 config is not null")
  void testDefaultS3ConfigNotNull() {
    assertThat(storageConfig.getS3()).isNotNull();
  }

  @Test
  @DisplayName("default local config is not null")
  void testDefaultLocalConfigNotNull() {
    assertThat(storageConfig.getLocal()).isNotNull();
  }

  @Test
  @DisplayName("setRuleSource updates the value")
  void testSetRuleSource() {
    storageConfig.setRuleSource("s3");

    assertThat(storageConfig.getRuleSource()).isEqualTo("s3");
  }

  @Test
  @DisplayName("setS3 updates the S3 config")
  void testSetS3Config() {
    StorageConfig.S3Config newS3Config = new StorageConfig.S3Config();
    newS3Config.setBucketName("my-bucket");

    storageConfig.setS3(newS3Config);

    assertThat(storageConfig.getS3()).isSameAs(newS3Config);
    assertThat(storageConfig.getS3().getBucketName()).isEqualTo("my-bucket");
  }

  @Test
  @DisplayName("setLocal updates the local config")
  void testSetLocalConfig() {
    StorageConfig.LocalConfig newLocalConfig = new StorageConfig.LocalConfig();
    newLocalConfig.setRulesDirectory("/custom/rules");

    storageConfig.setLocal(newLocalConfig);

    assertThat(storageConfig.getLocal()).isSameAs(newLocalConfig);
    assertThat(storageConfig.getLocal().getRulesDirectory()).isEqualTo("/custom/rules");
  }

  @Nested
  @DisplayName("S3Config")
  class S3ConfigTests {

    private StorageConfig.S3Config s3Config;

    @BeforeEach
    void setUp() {
      s3Config = new StorageConfig.S3Config();
    }

    @Test
    @DisplayName("default bucketName is 'local-rules'")
    void testDefaultBucketName() {
      assertThat(s3Config.getBucketName()).isEqualTo("local-rules");
    }

    @Test
    @DisplayName("default region is 'us-east-1'")
    void testDefaultRegion() {
      assertThat(s3Config.getRegion()).isEqualTo("us-east-1");
    }

    @Test
    @DisplayName("default endpoint is empty string")
    void testDefaultEndpoint() {
      assertThat(s3Config.getEndpoint()).isEmpty();
    }

    @Test
    @DisplayName("setBucketName updates the value")
    void testSetBucketName() {
      s3Config.setBucketName("production-rules");

      assertThat(s3Config.getBucketName()).isEqualTo("production-rules");
    }

    @Test
    @DisplayName("setRegion updates the value")
    void testSetRegion() {
      s3Config.setRegion("eu-west-1");

      assertThat(s3Config.getRegion()).isEqualTo("eu-west-1");
    }

    @Test
    @DisplayName("setEndpoint updates the value")
    void testSetEndpoint() {
      s3Config.setEndpoint("http://localhost:4566");

      assertThat(s3Config.getEndpoint()).isEqualTo("http://localhost:4566");
    }
  }

  @Nested
  @DisplayName("LocalConfig")
  class LocalConfigTests {

    private StorageConfig.LocalConfig localConfig;

    @BeforeEach
    void setUp() {
      localConfig = new StorageConfig.LocalConfig();
    }

    @Test
    @DisplayName("default rulesDirectory is 'src/main/resources/rules'")
    void testDefaultRulesDirectory() {
      assertThat(localConfig.getRulesDirectory()).isEqualTo("src/main/resources/rules");
    }

    @Test
    @DisplayName("setRulesDirectory updates the value")
    void testSetRulesDirectory() {
      localConfig.setRulesDirectory("/opt/drools/rules");

      assertThat(localConfig.getRulesDirectory()).isEqualTo("/opt/drools/rules");
    }
  }
}
