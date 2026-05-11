package com.company.drools.api.controller;

import com.company.drools.api.dto.RuleExecutionRequest;
import com.company.drools.api.dto.RuleExecutionResponse;
import com.company.drools.api.exception.RuleExecutionException;
import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.common.LogSanitizer;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuleExecutionController {

  private static final Logger log = LoggerFactory.getLogger(RuleExecutionController.class);

  private static final String METRIC_API_RESPONSE_TIME = "drools.api.response.time";
  private static final String METRIC_API_ERRORS        = "drools.api.errors";
  private static final String TAG_ENDPOINT             = "endpoint";
  private static final String TAG_STATUS               = "status";
  private static final String STATUS_ERROR             = "error";
  private static final String TAG_ERROR_TYPE           = "error_type";
  private static final String ENDPOINT_EXECUTE_RULE    = "/execute-rule";

  private final DroolsEngineService droolsEngineService;
  private final MeterRegistry meterRegistry;

  public RuleExecutionController(
      DroolsEngineService droolsEngineService, MeterRegistry meterRegistry) {
    this.droolsEngineService = droolsEngineService;
    this.meterRegistry = meterRegistry;
  }

  @PostMapping("/execute-rule")
  public ResponseEntity<RuleExecutionResponse> executeRule(
      @Valid @RequestBody RuleExecutionRequest request) {
    log.info(
        "Executing rule: {} with data keys: {}",
        LogSanitizer.sanitizeMessage(request.getRuleId()),
        LogSanitizer.safeDataRepresentation(request.getData()));

    // Start timing the API request
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      // Record API request
      meterRegistry.counter("drools.api.requests", TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE).increment();
      // Check if rule exists
      if (!droolsEngineService.hasRule(request.getRuleId())) {
        throw new RuleNotFoundException(request.getRuleId());
      }

      // Create a mutable copy of the input data for rule execution
      Map<String, Object> inputData = new HashMap<>(request.getData());

      // Execute the rule
      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule(request.getRuleId(), inputData);

      if (result.isSuccess()) {
        log.info(
            "Rule {} executed successfully in {}ms",
            LogSanitizer.sanitizeMessage(request.getRuleId()),
            result.getExecutionTimeMs());

        // Record successful response
        sample.stop(
            Timer.builder(METRIC_API_RESPONSE_TIME)
                .tag(TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE)
                .tag(TAG_STATUS,"success")
                .register(meterRegistry));

        RuleExecutionResponse response =
            RuleExecutionResponse.success(
                request.getRuleId(), result.getResult(), result.getExecutionTimeMs());

        return ResponseEntity.ok(response);
      } else {
        log.warn(
            "Rule {} execution failed: {}",
            LogSanitizer.sanitizeMessage(request.getRuleId()),
            LogSanitizer.sanitizeMessage(result.getErrorMessage()));
        throw new RuleExecutionException(request.getRuleId(), result.getErrorMessage());
      }

    } catch (RuleNotFoundException e) {
      // Record API error
      meterRegistry
          .counter(METRIC_API_ERRORS, TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE, TAG_ERROR_TYPE, "rule_not_found")
          .increment();
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE)
              .tag(TAG_STATUS, STATUS_ERROR)
              .register(meterRegistry));
      throw e;
    } catch (RuleExecutionException e) {
      // Record API error
      meterRegistry
          .counter(
              METRIC_API_ERRORS, TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE, TAG_ERROR_TYPE, "execution_failed")
          .increment();
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE)
              .tag(TAG_STATUS, STATUS_ERROR)
              .register(meterRegistry));
      throw e;
    } catch (Exception e) {
      log.error(
          "Unexpected error executing rule: {}",
          LogSanitizer.sanitizeMessage(request.getRuleId()),
          e);
      // Record API error
      meterRegistry
          .counter(METRIC_API_ERRORS, TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE, TAG_ERROR_TYPE, "unexpected")
          .increment();
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_EXECUTE_RULE)
              .tag(TAG_STATUS, STATUS_ERROR)
              .register(meterRegistry));
      throw new RuleExecutionException(
          request.getRuleId(), "Unexpected error during rule execution", e);
    }
  }
}
