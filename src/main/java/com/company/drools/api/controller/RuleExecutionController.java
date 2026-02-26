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
      meterRegistry.counter("drools.api.requests", "endpoint", "/execute-rule").increment();
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
            Timer.builder("drools.api.response.time")
                .tag("endpoint", "/execute-rule")
                .tag("status", "success")
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
          .counter("drools.api.errors", "endpoint", "/execute-rule", "error_type", "rule_not_found")
          .increment();
      sample.stop(
          Timer.builder("drools.api.response.time")
              .tag("endpoint", "/execute-rule")
              .tag("status", "error")
              .register(meterRegistry));
      throw e;
    } catch (RuleExecutionException e) {
      // Record API error
      meterRegistry
          .counter(
              "drools.api.errors", "endpoint", "/execute-rule", "error_type", "execution_failed")
          .increment();
      sample.stop(
          Timer.builder("drools.api.response.time")
              .tag("endpoint", "/execute-rule")
              .tag("status", "error")
              .register(meterRegistry));
      throw e;
    } catch (Exception e) {
      log.error(
          "Unexpected error executing rule: {}",
          LogSanitizer.sanitizeMessage(request.getRuleId()),
          e);
      // Record API error
      meterRegistry
          .counter("drools.api.errors", "endpoint", "/execute-rule", "error_type", "unexpected")
          .increment();
      sample.stop(
          Timer.builder("drools.api.response.time")
              .tag("endpoint", "/execute-rule")
              .tag("status", "error")
              .register(meterRegistry));
      throw new RuleExecutionException(
          request.getRuleId(), "Unexpected error during rule execution", e);
    }
  }
}
