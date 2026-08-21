package com.company.drools.storage;

import com.company.drools.api.exception.CircuitBreakerException;
import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.api.exception.RuleStorageException;
import com.company.drools.common.RuleIds;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component("s3RuleStorage")
public class S3RuleStorage implements RuleStorage {

  private static final Logger log = LoggerFactory.getLogger(S3RuleStorage.class);

  private static final String METRIC_STORAGE_OPERATION_TIME = "drools.storage.operation.time";
  private static final String TAG_OPERATION = "operation";
  private static final String TAG_STORAGE_TYPE = "storage_type";
  private static final String STORAGE_TYPE_S3 = "s3";
  private static final String TAG_STATUS = "status";
  private static final String FILE_EXT_DRL = ".drl";
  private static final String OP_GET_RULE = "getRule";

  private final S3Client s3Client;
  private final MeterRegistry meterRegistry;
  private final CircuitBreaker s3CircuitBreaker;

  @Value("${drools.s3.bucket-name}")
  private String bucketName;

  public S3RuleStorage(
      S3Client s3Client,
      MeterRegistry meterRegistry,
      @Qualifier("s3CircuitBreaker") CircuitBreaker s3CircuitBreaker) {
    this.s3Client = s3Client;
    this.meterRegistry = meterRegistry;
    this.s3CircuitBreaker = s3CircuitBreaker;
  }

  @Override
  public Optional<Rule> getRule(String ruleId) {
    log.debug("Loading rule from S3: {}", ruleId);

    String s3Key = ruleIdToS3Key(ruleId);

    // Start timing the storage operation
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      // Wrap S3 operations with circuit breaker
      Supplier<Optional<Rule>> s3Operation =
          CircuitBreaker.decorateSupplier(
              s3CircuitBreaker,
              () -> {
                try {
                  GetObjectRequest request =
                      GetObjectRequest.builder().bucket(bucketName).key(s3Key).build();

                  String content = s3Client.getObjectAsBytes(request).asUtf8String();

                  // Get object metadata for rule metadata
                  HeadObjectRequest headRequest =
                      HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build();

                  HeadObjectResponse headResponse = s3Client.headObject(headRequest);
                  RuleMetadata metadata = createMetadataFromS3Object(headResponse);

                  Rule rule = new Rule(ruleId, content, metadata);
                  log.debug("Successfully loaded rule: {} from S3 key: {}", ruleId, s3Key);

                  return Optional.of(rule);

                } catch (NoSuchKeyException _) {
                  log.warn("Rule not found in S3: {} (key: {})", ruleId, s3Key);
                  return Optional.empty();
                }
              });

      Optional<Rule> result = s3Operation.get();

      // Record successful storage operation
      sample.stop(
          Timer.builder(METRIC_STORAGE_OPERATION_TIME)
              .tag(TAG_OPERATION, OP_GET_RULE)
              .tag(TAG_STORAGE_TYPE, STORAGE_TYPE_S3)
              .tag(TAG_STATUS, result.isPresent() ? "success" : "not_found")
              .register(meterRegistry));

      return result;

    } catch (CallNotPermittedException e) {
      // Record circuit breaker open
      sample.stop(
          Timer.builder(METRIC_STORAGE_OPERATION_TIME)
              .tag(TAG_OPERATION, OP_GET_RULE)
              .tag(TAG_STORAGE_TYPE, STORAGE_TYPE_S3)
              .tag(TAG_STATUS, "circuit_breaker_open")
              .register(meterRegistry));

      throw new CircuitBreakerException(STORAGE_TYPE_S3, s3CircuitBreaker.getState().toString(), e);
    } catch (SdkException e) {
      // Record failed storage operation
      sample.stop(
          Timer.builder(METRIC_STORAGE_OPERATION_TIME)
              .tag(TAG_OPERATION, OP_GET_RULE)
              .tag(TAG_STORAGE_TYPE, STORAGE_TYPE_S3)
              .tag(TAG_STATUS, "error")
              .register(meterRegistry));

      throw new RuleStorageException("Failed to load rule from S3: " + ruleId, e);
    }
  }

  @Override
  public List<Rule> getAllRules() {
    log.debug("Loading all rules from S3 bucket: {}", bucketName);

    try {
      Supplier<List<Rule>> s3Operation =
          CircuitBreaker.decorateSupplier(s3CircuitBreaker, this::loadAllRulesFromS3);
      return s3Operation.get();

    } catch (CallNotPermittedException _) {
      log.warn("S3 circuit breaker is open — cannot load all rules");
      throw new CircuitBreakerException(STORAGE_TYPE_S3, "OPEN");
    }
  }

  private List<Rule> loadAllRulesFromS3() {
    List<Rule> rules = new ArrayList<>();

    try {
      ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucketName).build();

      ListObjectsV2Response response;
      do {
        response = s3Client.listObjectsV2(request);

        for (S3Object s3Object : response.contents()) {
          if (s3Object.key().endsWith(FILE_EXT_DRL)) {
            String ruleId = s3KeyToRuleId(s3Object.key());

            tryLoadAndAddRule(ruleId, s3Object, rules);
          }
        }

        request =
            ListObjectsV2Request.builder()
                .bucket(bucketName)
                .continuationToken(response.nextContinuationToken())
                .build();

      } while (Boolean.TRUE.equals(response.isTruncated()));

      log.info("Loaded {} rules from S3 bucket: {}", rules.size(), bucketName);
      return rules;

    } catch (SdkException e) {
      throw new RuleStorageException("Failed to load rules from S3", e);
    }
  }

  @Override
  public void saveRule(Rule rule) {
    log.debug("Saving rule to S3: {}", rule.getRuleId());

    String s3Key = ruleIdToS3Key(rule.getRuleId());

    try {
      PutObjectRequest request =
          PutObjectRequest.builder()
              .bucket(bucketName)
              .key(s3Key)
              .contentType("text/plain")
              .build();

      s3Client.putObject(request, RequestBody.fromString(rule.getContent()));
      log.info("Successfully saved rule: {} to S3 key: {}", rule.getRuleId(), s3Key);

    } catch (SdkException e) {
      throw new RuleStorageException("Failed to save rule to S3: " + rule.getRuleId(), e);
    }
  }

  @Override
  public void deleteRule(String ruleId) {
    log.debug("Deleting rule from S3: {}", ruleId);

    String s3Key = ruleIdToS3Key(ruleId);

    try {
      // Check if rule exists first
      if (!ruleExists(ruleId)) {
        throw new RuleNotFoundException("Rule not found: " + ruleId);
      }

      DeleteObjectRequest request =
          DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build();

      s3Client.deleteObject(request);
      log.info("Successfully deleted rule: {} from S3 key: {}", ruleId, s3Key);

    } catch (SdkException e) {
      throw new RuleStorageException("Failed to delete rule from S3: " + ruleId, e);
    }
  }

  @Override
  public boolean ruleExists(String ruleId) {
    String s3Key = ruleIdToS3Key(ruleId);

    try {
      HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build();

      s3Client.headObject(request);
      return true;

    } catch (NoSuchKeyException _) {
      return false;
    } catch (SdkException e) {
      log.warn("Error checking if rule exists: {} (key: {})", ruleId, s3Key, e);
      return false;
    }
  }

  @Override
  public void refreshCache() {
    log.info("Refreshing S3 rule storage cache (no-op for S3 storage)");
    // S3 storage doesn't maintain a local cache, so this is a no-op
    // The cache layer will be handled separately
  }

  @Override
  public void refreshRule(String ruleId) {
    log.info("Refreshing specific rule from S3: {}", ruleId);
    // For S3 storage, this is essentially a no-op since we always fetch from S3
    // The cache layer will handle invalidation
  }

  @Override
  public long getTotalRuleCount() {
    try {
      ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucketName).build();

      long count = 0;
      ListObjectsV2Response response;

      do {
        response = s3Client.listObjectsV2(request);
        count +=
            response.contents().stream().filter(obj -> obj.key().endsWith(FILE_EXT_DRL)).count();

        request =
            ListObjectsV2Request.builder()
                .bucket(bucketName)
                .continuationToken(response.nextContinuationToken())
                .build();

      } while (Boolean.TRUE.equals(response.isTruncated()));

      return count;

    } catch (SdkException e) {
      log.error("Failed to count rules in S3 bucket: {}", bucketName, e);
      return 0;
    }
  }

  @Override
  public List<String> getRuleIds() {
    try {
      ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucketName).build();

      List<String> ruleIds = new ArrayList<>();
      ListObjectsV2Response response;

      do {
        response = s3Client.listObjectsV2(request);
        List<String> pageRuleIds =
            response.contents().stream()
                .filter(obj -> obj.key().endsWith(FILE_EXT_DRL))
                .map(obj -> s3KeyToRuleId(obj.key()))
                .toList();
        ruleIds.addAll(pageRuleIds);

        request =
            ListObjectsV2Request.builder()
                .bucket(bucketName)
                .continuationToken(response.nextContinuationToken())
                .build();

      } while (Boolean.TRUE.equals(response.isTruncated()));

      return ruleIds;

    } catch (SdkException e) {
      log.error("Failed to list rule IDs from S3 bucket: {}", bucketName, e);
      return new ArrayList<>();
    }
  }

  /**
   * Transforms rule ID to S3 key path. Example: "pricing.discount.simple" ->
   * "pricing/discount/simple.drl"
   */
  private String ruleIdToS3Key(String ruleId) {
    // Validate the RAW rule ID before transformation. The previous guard checked contains("../")
    // AFTER the '.'→'/' replacement, by which point any ".." had already become "//" — so it never
    // fired for dotted input (dead code, finding S11). RuleIds rejects traversal on the raw value.
    RuleIds.requirePathSafe(ruleId);
    return ruleId.replace(".", "/") + FILE_EXT_DRL;
  }

  /**
   * Transforms S3 key path to rule ID. Example: "pricing/discount/simple.drl" ->
   * "pricing.discount.simple"
   */
  private void tryLoadAndAddRule(String ruleId, S3Object s3Object, List<Rule> rules) {
    try {
      String content =
          s3Client
              .getObjectAsBytes(
                  GetObjectRequest.builder().bucket(bucketName).key(s3Object.key()).build())
              .asUtf8String();
      RuleMetadata metadata = createMetadataFromS3Object(s3Object);
      rules.add(new Rule(ruleId, content, metadata));
    } catch (Exception e) {
      log.warn("Failed to load rule: {} from S3 key: {}", ruleId, s3Object.key(), e);
    }
  }

  private String s3KeyToRuleId(String s3Key) {
    String ruleId = s3Key;
    if (ruleId.endsWith(FILE_EXT_DRL)) {
      ruleId = ruleId.substring(0, ruleId.length() - 4);
    }
    return ruleId.replace("/", ".");
  }

  private RuleMetadata createMetadataFromS3Object(S3Object s3Object) {
    RuleMetadata metadata = RuleMetadata.createNew();
    if (s3Object.lastModified() != null) {
      metadata = metadata.withLastModified(s3Object.lastModified());
    }
    return metadata.withStatus(RuleMetadata.RuleStatus.ACTIVE);
  }

  private RuleMetadata createMetadataFromS3Object(HeadObjectResponse headResponse) {
    RuleMetadata metadata = RuleMetadata.createNew();
    if (headResponse.lastModified() != null) {
      metadata = metadata.withLastModified(headResponse.lastModified());
    }
    return metadata.withStatus(RuleMetadata.RuleStatus.ACTIVE);
  }
}
