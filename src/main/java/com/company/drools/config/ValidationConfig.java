package com.company.drools.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Configuration for input validation limits */
@Configuration
public class ValidationConfig {

  @Value("${drools.validation.rule-id.max-length:255}")
  private int ruleIdMaxLength;

  @Value("${drools.validation.data.max-fields:100}")
  private int dataMaxFields;

  @Value("${drools.validation.data.max-string-length:10000}")
  private int dataMaxStringLength;

  @Value("${drools.validation.data.max-number-value:1000000}")
  private long dataMaxNumberValue;

  @Value("${drools.validation.request.max-size-bytes:1048576}")
  private long requestMaxSizeBytes; // 1MB default

  public int getRuleIdMaxLength() {
    return ruleIdMaxLength;
  }

  public int getDataMaxFields() {
    return dataMaxFields;
  }

  public int getDataMaxStringLength() {
    return dataMaxStringLength;
  }

  public long getDataMaxNumberValue() {
    return dataMaxNumberValue;
  }

  public long getRequestMaxSizeBytes() {
    return requestMaxSizeBytes;
  }
}
