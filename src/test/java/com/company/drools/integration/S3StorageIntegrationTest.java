package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.S3RuleStorage;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

@DisplayName("S3 Storage Integration Tests")
@Testcontainers
class S3StorageIntegrationTest {

  private static final String BUCKET_NAME = "test-rules";

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
          .withServices(LocalStackContainer.Service.S3);

  private static S3Client s3Client;
  private S3RuleStorage s3RuleStorage;

  @BeforeAll
  static void setupS3() {
    s3Client =
        S3Client.builder()
            .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        localstack.getAccessKey(), localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .forcePathStyle(true)
            .build();

    s3Client.createBucket(b -> b.bucket(BUCKET_NAME));
  }

  @BeforeEach
  void setUp() throws Exception {
    // Clear all objects in the bucket before each test
    clearBucket();

    // Build S3RuleStorage with real dependencies
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of("s3-test", CircuitBreakerConfig.ofDefaults());
    TimeoutConfig timeoutConfig = new TimeoutConfig();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    s3RuleStorage = new S3RuleStorage(s3Client, meterRegistry, timeoutConfig, circuitBreaker);

    // Inject bucket name via reflection (matches how @Value works)
    Field bucketNameField = S3RuleStorage.class.getDeclaredField("bucketName");
    bucketNameField.setAccessible(true);
    bucketNameField.set(s3RuleStorage, BUCKET_NAME);
  }

  private void clearBucket() {
    var listResponse =
        s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET_NAME).build());
    if (!listResponse.contents().isEmpty()) {
      var objectIds =
          listResponse.contents().stream()
              .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
              .toList();
      s3Client.deleteObjects(
          DeleteObjectsRequest.builder()
              .bucket(BUCKET_NAME)
              .delete(Delete.builder().objects(objectIds).build())
              .build());
    }
  }

  // ========================================================================
  // Happy Path Tests
  // ========================================================================

  @Test
  @DisplayName("Save and retrieve rule end-to-end")
  void testSaveAndRetrieveRule_EndToEnd_Success() {
    // Given
    String ruleId = "pricing.discount.simple";
    String content =
        """
        package com.company.rules.pricing.discount

        import java.util.Map

        rule "Simple Discount"
        when
            $data : Map(this["amount"] != null)
        then
            Double amount = (Double) $data.get("amount");
            $data.put("discount", amount * 0.10);
        end
        """;
    Rule rule = new Rule(ruleId, content, RuleMetadata.createNew());

    // When - save then retrieve
    s3RuleStorage.saveRule(rule);
    Optional<Rule> retrieved = s3RuleStorage.getRule(ruleId);

    // Then
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getRuleId()).isEqualTo(ruleId);
    assertThat(retrieved.get().getContent()).isEqualTo(content);
    assertThat(retrieved.get().getMetadata()).isNotNull();
    assertThat(retrieved.get().getMetadata().getStatus())
        .isEqualTo(RuleMetadata.RuleStatus.ACTIVE);

    // Verify exists check
    assertThat(s3RuleStorage.ruleExists(ruleId)).isTrue();
  }

  @Test
  @DisplayName("Get all rules returns multiple saved rules")
  void testGetAllRules_MultipleRules_ReturnsAll() {
    // Given - save 3 rules
    Rule rule1 =
        new Rule(
            "pricing.discount.simple",
            "package com.company.rules.pricing.discount\nrule \"R1\" when then end",
            RuleMetadata.createNew());
    Rule rule2 =
        new Rule(
            "pricing.shipping.standard",
            "package com.company.rules.pricing.shipping\nrule \"R2\" when then end",
            RuleMetadata.createNew());
    Rule rule3 =
        new Rule(
            "validation.customer.age",
            "package com.company.rules.validation.customer\nrule \"R3\" when then end",
            RuleMetadata.createNew());

    s3RuleStorage.saveRule(rule1);
    s3RuleStorage.saveRule(rule2);
    s3RuleStorage.saveRule(rule3);

    // When
    List<Rule> allRules = s3RuleStorage.getAllRules();

    // Then
    assertThat(allRules).hasSize(3);
    assertThat(allRules)
        .extracting(Rule::getRuleId)
        .containsExactlyInAnyOrder(
            "pricing.discount.simple",
            "pricing.shipping.standard",
            "validation.customer.age");

    // Verify rule count
    assertThat(s3RuleStorage.getTotalRuleCount()).isEqualTo(3);

    // Verify rule IDs list
    assertThat(s3RuleStorage.getRuleIds())
        .containsExactlyInAnyOrder(
            "pricing.discount.simple",
            "pricing.shipping.standard",
            "validation.customer.age");
  }

  @Test
  @DisplayName("Delete existing rule removes it from S3")
  void testDeleteRule_ExistingRule_RemovedFromS3() {
    // Given
    String ruleId = "pricing.discount.temporary";
    Rule rule =
        new Rule(
            ruleId,
            "package com.company.rules.pricing.discount\nrule \"Temp\" when then end",
            RuleMetadata.createNew());

    s3RuleStorage.saveRule(rule);
    assertThat(s3RuleStorage.ruleExists(ruleId)).isTrue();

    // When
    s3RuleStorage.deleteRule(ruleId);

    // Then
    assertThat(s3RuleStorage.ruleExists(ruleId)).isFalse();
    assertThat(s3RuleStorage.getRule(ruleId)).isEmpty();

    // Deleting again should throw
    assertThatThrownBy(() -> s3RuleStorage.deleteRule(ruleId))
        .isInstanceOf(RuleNotFoundException.class);
  }

  // ========================================================================
  // Real S3 Operations Tests
  // ========================================================================

  @Test
  @DisplayName("Rule ID transformation works with real S3: dots to slashes")
  void testRuleIdTransformation_RealS3_DotsToSlashes() {
    // Given - rule ID with dots that should become S3 path with slashes
    String ruleId = "pricing.discount.black-friday";
    String content =
        "package com.company.rules.pricing.discount\nrule \"BlackFriday\" when then end";
    Rule rule = new Rule(ruleId, content, RuleMetadata.createNew());

    // When
    s3RuleStorage.saveRule(rule);

    // Then - verify the S3 key was created with slashes
    var listResponse =
        s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET_NAME).build());
    assertThat(listResponse.contents()).hasSize(1);
    assertThat(listResponse.contents().get(0).key())
        .isEqualTo("pricing/discount/black-friday.drl");

    // Verify round-trip: retrieve by dot-separated ID
    Optional<Rule> retrieved = s3RuleStorage.getRule(ruleId);
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getRuleId()).isEqualTo(ruleId);
    assertThat(retrieved.get().getContent()).isEqualTo(content);
  }

  @Test
  @DisplayName("Large rule content can be saved and retrieved intact")
  void testLargeRuleContent_SaveAndRetrieve_Success() {
    // Given - build a large rule (~50KB) with many conditions
    StringBuilder contentBuilder = new StringBuilder();
    contentBuilder.append("package com.company.rules.large\n\n");
    contentBuilder.append("import java.util.Map\n\n");

    for (int i = 0; i < 200; i++) {
      contentBuilder.append(
          String.format(
              """
              rule "Large Rule %d"
              when
                  $data : Map(this["field_%d"] != null)
              then
                  $data.put("result_%d", "processed");
                  $data.put("timestamp_%d", System.currentTimeMillis());
              end

              """,
              i, i, i, i));
    }

    String largeContent = contentBuilder.toString();
    assertThat(largeContent.length()).isGreaterThan(10000);

    String ruleId = "large.rule.content";
    Rule rule = new Rule(ruleId, largeContent, RuleMetadata.createNew());

    // When
    s3RuleStorage.saveRule(rule);
    Optional<Rule> retrieved = s3RuleStorage.getRule(ruleId);

    // Then
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getContent()).isEqualTo(largeContent);
    assertThat(retrieved.get().getContent().length()).isEqualTo(largeContent.length());
  }

  @Test
  @DisplayName("Concurrent operations on multiple rules do not lose data")
  void testConcurrentOperations_MultipleRules_NoDataLoss() throws Exception {
    // Given
    int numRules = 20;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch latch = new CountDownLatch(numRules);
    AtomicInteger errors = new AtomicInteger(0);

    // When - save rules concurrently
    for (int i = 0; i < numRules; i++) {
      final int index = i;
      executor.submit(
          () -> {
            try {
              String ruleId = "concurrent.rules.rule-" + index;
              String content =
                  String.format(
                      "package com.company.rules.concurrent.rules\nrule \"Rule%d\" when then end",
                      index);
              Rule rule = new Rule(ruleId, content, RuleMetadata.createNew());
              s3RuleStorage.saveRule(rule);
            } catch (Exception e) {
              errors.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
    }
    latch.await();
    executor.shutdown();

    // Then - all rules should be present
    assertThat(errors.get()).isZero();
    assertThat(s3RuleStorage.getTotalRuleCount()).isEqualTo(numRules);

    List<Rule> allRules = s3RuleStorage.getAllRules();
    assertThat(allRules).hasSize(numRules);

    // Verify each rule can be individually retrieved
    for (int i = 0; i < numRules; i++) {
      String ruleId = "concurrent.rules.rule-" + i;
      Optional<Rule> rule = s3RuleStorage.getRule(ruleId);
      assertThat(rule)
          .as("Rule %s should exist", ruleId)
          .isPresent();
    }
  }
}
