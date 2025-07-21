package com.company.drools.api.controller;

import com.company.drools.api.dto.RuleExecutionRequest;
import com.company.drools.api.dto.RuleExecutionResponse;
import com.company.drools.api.exception.RuleExecutionException;
import com.company.drools.api.exception.RuleNotFoundException;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleExecutor;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class RuleExecutionController {

  private static final Logger log = LoggerFactory.getLogger(RuleExecutionController.class);

  private final DroolsEngineService droolsEngineService;

  public RuleExecutionController(DroolsEngineService droolsEngineService) {
    this.droolsEngineService = droolsEngineService;
  }

  @PostMapping("/execute-rule")
  public ResponseEntity<RuleExecutionResponse> executeRule(@Valid @RequestBody RuleExecutionRequest request) {
    log.info("Executing rule: {} with data keys: {}", request.getRuleId(), request.getData().keySet());
    
    try {
      // Check if rule exists
      if (!droolsEngineService.hasRule(request.getRuleId())) {
        throw new RuleNotFoundException(request.getRuleId());
      }
      
      // Create a mutable copy of the input data for rule execution
      Map<String, Object> inputData = new HashMap<>(request.getData());
      
      // Execute the rule
      RuleExecutor.ExecutionResult result = droolsEngineService.executeRule(request.getRuleId(), inputData);
      
      if (result.isSuccess()) {
        log.info("Rule {} executed successfully in {}ms", request.getRuleId(), result.getExecutionTimeMs());
        
        RuleExecutionResponse response = RuleExecutionResponse.success(
            request.getRuleId(),
            result.getResult(),
            result.getExecutionTimeMs()
        );
        
        return ResponseEntity.ok(response);
      } else {
        log.warn("Rule {} execution failed: {}", request.getRuleId(), result.getErrorMessage());
        throw new RuleExecutionException(request.getRuleId(), result.getErrorMessage());
      }
      
    } catch (RuleNotFoundException | RuleExecutionException e) {
      // These will be handled by GlobalExceptionHandler
      throw e;
    } catch (Exception e) {
      log.error("Unexpected error executing rule: {}", request.getRuleId(), e);
      throw new RuleExecutionException(request.getRuleId(), "Unexpected error during rule execution: " + e.getMessage(), e);
    }
  }
}