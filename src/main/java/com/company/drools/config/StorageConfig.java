package com.company.drools.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "drools")
public class StorageConfig {

  private String ruleSource = "local";
  private S3Config s3 = new S3Config();
  private LocalConfig local = new LocalConfig();

  public String getRuleSource() {
    return ruleSource;
  }

  public void setRuleSource(String ruleSource) {
    this.ruleSource = ruleSource;
  }

  public S3Config getS3() {
    return s3;
  }

  public void setS3(S3Config s3) {
    this.s3 = s3;
  }

  public LocalConfig getLocal() {
    return local;
  }

  public void setLocal(LocalConfig local) {
    this.local = local;
  }

  public static class S3Config {
    private String bucketName = "local-rules";
    private String region = "us-east-1";
    private String endpoint = "";

    public String getBucketName() {
      return bucketName;
    }

    public void setBucketName(String bucketName) {
      this.bucketName = bucketName;
    }

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
  }

  public static class LocalConfig {
    private String rulesDirectory = "src/main/resources/rules";

    public String getRulesDirectory() {
      return rulesDirectory;
    }

    public void setRulesDirectory(String rulesDirectory) {
      this.rulesDirectory = rulesDirectory;
    }
  }
}
