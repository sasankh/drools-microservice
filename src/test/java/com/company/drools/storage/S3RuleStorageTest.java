package com.company.drools.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.company.drools.api.exception.CircuitBreakerException;
import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@DisplayName("S3RuleStorage")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class S3RuleStorageTest {

  @Mock private S3Client s3Client;
  @Mock private CircuitBreaker s3CircuitBreaker;

  private MeterRegistry meterRegistry;
  private S3RuleStorage s3RuleStorage;

  @BeforeEach
  void setUp() throws Exception {
    // Use a real SimpleMeterRegistry - handles Timer.start() and Timer.builder().register()
    meterRegistry = new SimpleMeterRegistry();

    s3RuleStorage = new S3RuleStorage(s3Client, meterRegistry, s3CircuitBreaker);

    // Set the bucketName field via reflection since it's @Value-injected
    Field bucketNameField = S3RuleStorage.class.getDeclaredField("bucketName");
    bucketNameField.setAccessible(true);
    bucketNameField.set(s3RuleStorage, "test-bucket");
  }

  // --- Helper methods ---

  private void setupCircuitBreakerPassthrough() {
    when(s3CircuitBreaker.getName()).thenReturn("s3");
    when(s3CircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
  }

  private void setupCircuitBreakerOpen() {
    when(s3CircuitBreaker.getName()).thenReturn("s3");
    when(s3CircuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
    CircuitBreakerConfig cbConfig = CircuitBreakerConfig.ofDefaults();
    when(s3CircuitBreaker.getCircuitBreakerConfig()).thenReturn(cbConfig);

    doThrow(CallNotPermittedException.createCallNotPermittedException(s3CircuitBreaker))
        .when(s3CircuitBreaker)
        .acquirePermission();
  }

  @SuppressWarnings("unchecked")
  private ResponseBytes<GetObjectResponse> createMockResponseBytes(String content) {
    ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
    when(responseBytes.asUtf8String()).thenReturn(content);
    return responseBytes;
  }

  private HeadObjectResponse createHeadObjectResponse() {
    return HeadObjectResponse.builder().lastModified(Instant.now()).build();
  }

  private S3Object createS3Object(String key) {
    return S3Object.builder().key(key).lastModified(Instant.now()).size(100L).build();
  }

  private static final String SAMPLE_DRL = "package com.company.rules\nrule \"test\" when then end";

  // ========================================================================
  // Happy Path Tests
  // ========================================================================

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("getRule returns rule when it exists in S3")
    void testGetRule_ExistingRule_ReturnsRule() {
      // Given
      setupCircuitBreakerPassthrough();
      String ruleId = "pricing.discount.simple";
      String ruleContent =
          "package com.company.rules.pricing.discount\nrule \"simple\" when then end";

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(ruleContent);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());

      // When
      Optional<Rule> result = s3RuleStorage.getRule(ruleId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getRuleId()).isEqualTo(ruleId);
      assertThat(result.get().getContent()).isEqualTo(ruleContent);
    }

    @Test
    @DisplayName("getAllRules returns all DRL files from S3")
    void testGetAllRules_ReturnsAllDrlFiles() {
      // Given
      List<S3Object> objects =
          List.of(
              createS3Object("pricing/discount/simple.drl"),
              createS3Object("validation/input/basic.drl"),
              createS3Object("README.md")); // Non-DRL file should be filtered

      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

      // When
      List<Rule> rules = s3RuleStorage.getAllRules();

      // Then
      assertThat(rules).hasSize(2);
      assertThat(rules)
          .extracting(Rule::getRuleId)
          .containsExactlyInAnyOrder("pricing.discount.simple", "validation.input.basic");
    }

    @Test
    @DisplayName("saveRule stores rule content in S3")
    void testSaveRule_Success_StoresInS3() {
      // Given
      String ruleId = "pricing.discount.simple";
      Rule rule = new Rule(ruleId, SAMPLE_DRL, RuleMetadata.createNew());

      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      // When
      s3RuleStorage.saveRule(rule);

      // Then
      ArgumentCaptor<PutObjectRequest> requestCaptor =
          ArgumentCaptor.forClass(PutObjectRequest.class);
      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

      PutObjectRequest capturedRequest = requestCaptor.getValue();
      assertThat(capturedRequest.bucket()).isEqualTo("test-bucket");
      assertThat(capturedRequest.key()).isEqualTo("pricing/discount/simple.drl");
      assertThat(capturedRequest.contentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("deleteRule removes rule from S3")
    void testDeleteRule_ExistingRule_RemovesFromS3() {
      // Given
      String ruleId = "pricing.discount.simple";

      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());
      when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
          .thenReturn(DeleteObjectResponse.builder().build());

      // When
      s3RuleStorage.deleteRule(ruleId);

      // Then
      ArgumentCaptor<DeleteObjectRequest> requestCaptor =
          ArgumentCaptor.forClass(DeleteObjectRequest.class);
      verify(s3Client).deleteObject(requestCaptor.capture());

      DeleteObjectRequest capturedRequest = requestCaptor.getValue();
      assertThat(capturedRequest.bucket()).isEqualTo("test-bucket");
      assertThat(capturedRequest.key()).isEqualTo("pricing/discount/simple.drl");
    }
  }

  // ========================================================================
  // Rule ID Transformation Tests
  // ========================================================================

  @Nested
  @DisplayName("Rule ID Transformation")
  class RuleIdTransformation {

    @Test
    @DisplayName("getRule transforms dot-separated ruleId to S3 key path")
    void testGetRule_TransformsRuleIdToS3Key() {
      // Given
      setupCircuitBreakerPassthrough();
      String ruleId = "pricing.discount.simple";

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());

      // When
      s3RuleStorage.getRule(ruleId);

      // Then - verify the S3 key used is "pricing/discount/simple.drl"
      ArgumentCaptor<GetObjectRequest> requestCaptor =
          ArgumentCaptor.forClass(GetObjectRequest.class);
      verify(s3Client).getObjectAsBytes(requestCaptor.capture());

      assertThat(requestCaptor.getValue().key()).isEqualTo("pricing/discount/simple.drl");
      assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
    }

    @Test
    @DisplayName("getAllRules transforms S3 key paths back to dot-separated ruleIds")
    void testGetAllRules_TransformsS3KeyToRuleId() {
      // Given
      List<S3Object> objects =
          List.of(
              createS3Object("pricing/discount/simple.drl"),
              createS3Object("validation/input/basic.drl"));

      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

      // When
      List<Rule> rules = s3RuleStorage.getAllRules();

      // Then
      assertThat(rules)
          .extracting(Rule::getRuleId)
          .containsExactlyInAnyOrder("pricing.discount.simple", "validation.input.basic");
    }
  }

  // ========================================================================
  // Circuit Breaker Integration Tests
  // ========================================================================

  @Nested
  @DisplayName("Circuit Breaker Integration")
  class CircuitBreakerIntegration {

    @Test
    @DisplayName("getRule throws CircuitBreakerException when CB is open")
    void testGetRule_CircuitBreakerOpen_ThrowsException() {
      // Given
      setupCircuitBreakerOpen();
      String ruleId = "pricing.discount.simple";

      // When / Then
      assertThatThrownBy(() -> s3RuleStorage.getRule(ruleId))
          .isInstanceOf(CircuitBreakerException.class)
          .hasMessageContaining("Circuit breaker")
          .hasMessageContaining("s3");

      // Verify S3 was never called since CB is open
      verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("getRule triggers circuit breaker recording on S3 failure")
    void testGetRule_S3Failure_TriggersCircuitBreaker() {
      // Given
      setupCircuitBreakerPassthrough();
      String ruleId = "pricing.discount.simple";

      SdkException s3Error = SdkException.builder().message("S3 connection timeout").build();
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(s3Error);

      // When / Then
      assertThatThrownBy(() -> s3RuleStorage.getRule(ruleId))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to load rule from S3");

      // Verify the circuit breaker recorded the error
      verify(s3CircuitBreaker).onError(anyLong(), any(), any());
    }

    @Test
    @DisplayName("getRule succeeds when circuit breaker is closed")
    void testGetRule_CircuitBreakerClosed_AllowsRequests() {
      // Given
      setupCircuitBreakerPassthrough();
      String ruleId = "pricing.discount.simple";

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());

      // When
      Optional<Rule> result = s3RuleStorage.getRule(ruleId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getRuleId()).isEqualTo(ruleId);

      // Verify CB permitted the call and was notified of the result
      verify(s3CircuitBreaker).acquirePermission();
      verify(s3CircuitBreaker).onResult(anyLong(), any(), any());
    }
  }

  // ========================================================================
  // Error Handling Tests
  // ========================================================================

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("getRule returns empty Optional when rule not found in S3")
    void testGetRule_NotFound_ReturnsEmptyOptional() {
      // Given
      setupCircuitBreakerPassthrough();
      String ruleId = "nonexistent.rule";

      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().message("Key not found").build());

      // When
      Optional<Rule> result = s3RuleStorage.getRule(ruleId);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteRule throws RuleNotFoundException for non-existent rule")
    void testDeleteRule_NotFound_ThrowsException() {
      // Given
      String ruleId = "nonexistent.rule";

      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().message("Key not found").build());

      // When / Then
      assertThatThrownBy(() -> s3RuleStorage.deleteRule(ruleId))
          .isInstanceOf(RuleNotFoundException.class)
          .hasMessageContaining("Rule not found");

      // Verify deleteObject was never called
      verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("getAllRules throws RuntimeException on S3 error")
    void testGetAllRules_S3Error_ThrowsRuntimeException() {
      // Given
      SdkException s3Error = SdkException.builder().message("Access denied").build();
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(s3Error);

      // When / Then
      assertThatThrownBy(() -> s3RuleStorage.getAllRules())
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to load rules from S3")
          .hasCauseInstanceOf(SdkException.class);
    }
  }

  // ========================================================================
  // Pagination & Metrics Tests
  // ========================================================================

  @Nested
  @DisplayName("Pagination and Metrics")
  class PaginationAndMetrics {

    @Test
    @DisplayName("getAllRules handles pagination for large number of rules")
    void testGetAllRules_LargeNumberOfRules_HandlesPagination() {
      // Given - simulate 2 pages of results
      List<S3Object> page1Objects = new ArrayList<>();
      for (int i = 0; i < 1000; i++) {
        page1Objects.add(createS3Object("rules/batch1/rule-" + i + ".drl"));
      }

      List<S3Object> page2Objects = new ArrayList<>();
      for (int i = 0; i < 500; i++) {
        page2Objects.add(createS3Object("rules/batch2/rule-" + i + ".drl"));
      }

      ListObjectsV2Response page1Response =
          ListObjectsV2Response.builder()
              .contents(page1Objects)
              .isTruncated(true)
              .nextContinuationToken("token-page2")
              .build();

      ListObjectsV2Response page2Response =
          ListObjectsV2Response.builder().contents(page2Objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(page1Response)
          .thenReturn(page2Response);

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

      // When
      List<Rule> rules = s3RuleStorage.getAllRules();

      // Then
      assertThat(rules).hasSize(1500);

      // Verify listObjectsV2 was called twice (two pages)
      verify(s3Client, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    @DisplayName("getRule records timing metrics on successful retrieval")
    void testGetRule_RecordsTimingMetrics() {
      // Given
      setupCircuitBreakerPassthrough();
      String ruleId = "pricing.discount.simple";

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());

      // When
      Optional<Rule> result = s3RuleStorage.getRule(ruleId);

      // Then
      assertThat(result).isPresent();

      // Verify that the timer "drools.storage.operation.time" was recorded
      // SimpleMeterRegistry tracks all registered meters
      assertThat(meterRegistry.find("drools.storage.operation.time").timer()).isNotNull();
      assertThat(meterRegistry.find("drools.storage.operation.time").timer().count()).isEqualTo(1);
    }
  }

  // ========================================================================
  // getTotalRuleCount Tests
  // ========================================================================

  @Nested
  @DisplayName("getTotalRuleCount")
  class GetTotalRuleCount {

    @Test
    @DisplayName("counts only .drl files")
    void testCountsOnlyDrlFiles() {
      List<S3Object> objects =
          List.of(
              createS3Object("pricing/discount/simple.drl"),
              createS3Object("README.md"),
              createS3Object("pricing/discount/vip.drl"));

      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      assertThat(s3RuleStorage.getTotalRuleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("handles pagination")
    void testHandlesPagination() {
      List<S3Object> page1 = List.of(createS3Object("rules/rule1.drl"));
      ListObjectsV2Response response1 =
          ListObjectsV2Response.builder()
              .contents(page1)
              .isTruncated(true)
              .nextContinuationToken("token")
              .build();

      List<S3Object> page2 =
          List.of(createS3Object("rules/rule2.drl"), createS3Object("rules/rule3.drl"));
      ListObjectsV2Response response2 =
          ListObjectsV2Response.builder().contents(page2).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(response1)
          .thenReturn(response2);

      assertThat(s3RuleStorage.getTotalRuleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("returns 0 on S3 error")
    void testReturnsZeroOnError() {
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenThrow(SdkException.builder().message("Access denied").build());

      assertThat(s3RuleStorage.getTotalRuleCount()).isZero();
    }

    @Test
    @DisplayName("returns 0 for empty bucket")
    void testEmptyBucket() {
      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(new ArrayList<>()).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      assertThat(s3RuleStorage.getTotalRuleCount()).isZero();
    }
  }

  // ========================================================================
  // getRuleIds Tests
  // ========================================================================

  @Nested
  @DisplayName("getRuleIds")
  class GetRuleIds {

    @Test
    @DisplayName("returns rule IDs transformed from S3 keys")
    void testReturnsTransformedRuleIds() {
      List<S3Object> objects =
          List.of(
              createS3Object("pricing/discount/simple.drl"),
              createS3Object("validation/customer/credit.drl"),
              createS3Object("README.md"));

      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      List<String> ruleIds = s3RuleStorage.getRuleIds();

      assertThat(ruleIds)
          .containsExactlyInAnyOrder("pricing.discount.simple", "validation.customer.credit");
    }

    @Test
    @DisplayName("handles pagination")
    void testHandlesPagination() {
      List<S3Object> page1 = List.of(createS3Object("rules/rule1.drl"));
      ListObjectsV2Response response1 =
          ListObjectsV2Response.builder()
              .contents(page1)
              .isTruncated(true)
              .nextContinuationToken("token")
              .build();

      List<S3Object> page2 = List.of(createS3Object("rules/rule2.drl"));
      ListObjectsV2Response response2 =
          ListObjectsV2Response.builder().contents(page2).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(response1)
          .thenReturn(response2);

      assertThat(s3RuleStorage.getRuleIds()).hasSize(2);
    }

    @Test
    @DisplayName("returns empty list on S3 error")
    void testReturnsEmptyOnError() {
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenThrow(SdkException.builder().message("Access denied").build());

      assertThat(s3RuleStorage.getRuleIds()).isEmpty();
    }
  }

  // ========================================================================
  // saveRule Error Handling Tests
  // ========================================================================

  @Nested
  @DisplayName("saveRule Error Handling")
  class SaveRuleErrorHandling {

    @Test
    @DisplayName("throws RuntimeException on S3 error")
    void testSaveRuleS3Error() {
      Rule rule = new Rule("test.rule", SAMPLE_DRL, RuleMetadata.createNew());

      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenThrow(SdkException.builder().message("Permission denied").build());

      assertThatThrownBy(() -> s3RuleStorage.saveRule(rule))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to save rule to S3");
    }
  }

  // ========================================================================
  // ruleExists Edge Cases
  // ========================================================================

  @Nested
  @DisplayName("ruleExists")
  class RuleExists {

    @Test
    @DisplayName("returns true when rule exists")
    void testReturnsTrue() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());

      assertThat(s3RuleStorage.ruleExists("pricing.discount.simple")).isTrue();
    }

    @Test
    @DisplayName("returns false for NoSuchKeyException")
    void testReturnsFalseNotFound() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().message("Not found").build());

      assertThat(s3RuleStorage.ruleExists("nonexistent.rule")).isFalse();
    }

    @Test
    @DisplayName("returns false on generic S3 error")
    void testReturnsFalseOnGenericError() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenThrow(SdkException.builder().message("Access denied").build());

      assertThat(s3RuleStorage.ruleExists("any.rule")).isFalse();
    }
  }

  // ========================================================================
  // deleteRule S3 Error Tests
  // ========================================================================

  @Nested
  @DisplayName("deleteRule Error Handling")
  class DeleteRuleErrorHandling {

    @Test
    @DisplayName("throws RuntimeException on S3 delete error")
    void testDeleteS3Error() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(createHeadObjectResponse());
      when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
          .thenThrow(SdkException.builder().message("Delete failed").build());

      assertThatThrownBy(() -> s3RuleStorage.deleteRule("test.rule"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to delete rule from S3");
    }
  }

  // ========================================================================
  // getAllRules Partial Failure Tests
  // ========================================================================

  @Nested
  @DisplayName("getAllRules Partial Failures")
  class GetAllRulesPartialFailures {

    @Test
    @DisplayName("continues loading when individual rule fails")
    void testContinuesOnIndividualFailure() {
      List<S3Object> objects =
          List.of(createS3Object("rules/rule1.drl"), createS3Object("rules/rule2.drl"));

      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      // First rule succeeds, second fails
      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
          .thenReturn(responseBytes)
          .thenThrow(SdkException.builder().message("Timeout").build());

      List<Rule> rules = s3RuleStorage.getAllRules();

      assertThat(rules).hasSize(1);
    }
  }

  // ========================================================================
  // Metadata Edge Cases
  // ========================================================================

  @Nested
  @DisplayName("Metadata Creation")
  class MetadataCreation {

    @Test
    @DisplayName("handles S3Object with null lastModified")
    void testS3ObjectNullLastModified() {
      S3Object s3Object = S3Object.builder().key("rules/test.drl").size(100L).build();

      List<S3Object> objects = List.of(s3Object);
      ListObjectsV2Response response =
          ListObjectsV2Response.builder().contents(objects).isTruncated(false).build();

      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

      List<Rule> rules = s3RuleStorage.getAllRules();

      assertThat(rules).hasSize(1);
      assertThat(rules.get(0).getMetadata().getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    }

    @Test
    @DisplayName("handles HeadObjectResponse with null lastModified")
    void testHeadObjectNullLastModified() {
      setupCircuitBreakerPassthrough();

      ResponseBytes<GetObjectResponse> responseBytes = createMockResponseBytes(SAMPLE_DRL);
      when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

      HeadObjectResponse headResponse = HeadObjectResponse.builder().build();
      when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

      Optional<Rule> result = s3RuleStorage.getRule("test.rule");

      assertThat(result).isPresent();
      assertThat(result.get().getMetadata().getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    }
  }
}
