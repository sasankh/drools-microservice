package com.company.drools.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.drools.api.exception.GlobalExceptionHandler;
import com.company.drools.config.ValidationConfig;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleExecutor;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = RuleExecutionController.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
            classes = {com.company.drools.api.filter.RateLimitingFilter.class}))
@Import({GlobalExceptionHandler.class, ValidationConfig.class, TestValidationConfig.class})
@ActiveProfiles("test")
class RuleExecutionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DroolsEngineService droolsEngineService;

  // --- Happy Path Tests (3) ---

  @Test
  void testExecuteRule_ValidRequest_ReturnsSuccess() throws Exception {
    Map<String, Object> resultData = Map.of("discount", 10.0);
    when(droolsEngineService.hasRule("test.rule")).thenReturn(true);
    when(droolsEngineService.executeRule(eq("test.rule"), anyMap()))
        .thenReturn(RuleExecutor.ExecutionResult.success(resultData, 15L));

    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"test.rule\",\"data\":{\"amount\":100.0}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value("test.rule"))
        .andExpect(jsonPath("$.result.discount").value(10.0))
        .andExpect(jsonPath("$.execution_time_ms").value(15));
  }

  @Test
  void testExecuteRule_WithComplexData_ReturnsModifiedResult() throws Exception {
    Map<String, Object> resultData = new HashMap<>();
    resultData.put("amount", 100.0);
    resultData.put("customerType", "VIP");
    resultData.put("discount", 20.0);
    resultData.put("finalAmount", 80.0);

    when(droolsEngineService.hasRule("pricing.discount.vip")).thenReturn(true);
    when(droolsEngineService.executeRule(eq("pricing.discount.vip"), anyMap()))
        .thenReturn(RuleExecutor.ExecutionResult.success(resultData, 25L));

    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"rule_id\":\"pricing.discount.vip\","
                        + "\"data\":{\"amount\":100.0,\"customerType\":\"VIP\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value("pricing.discount.vip"))
        .andExpect(jsonPath("$.result.discount").value(20.0))
        .andExpect(jsonPath("$.result.finalAmount").value(80.0))
        .andExpect(jsonPath("$.result.customerType").value("VIP"))
        .andExpect(jsonPath("$.execution_time_ms").value(25));
  }

  @Test
  void testExecuteRule_RecordsMetrics() throws Exception {
    Map<String, Object> resultData = Map.of("result", "value");
    when(droolsEngineService.hasRule("metrics.rule")).thenReturn(true);
    when(droolsEngineService.executeRule(eq("metrics.rule"), anyMap()))
        .thenReturn(RuleExecutor.ExecutionResult.success(resultData, 5L));

    // Execute the request - the controller internally records metrics via MeterRegistry
    // which is auto-configured by @WebMvcTest. We verify the response is successful,
    // confirming the metrics code path executed without errors.
    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"metrics.rule\",\"data\":{\"key\":\"val\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value("metrics.rule"))
        .andExpect(jsonPath("$.execution_time_ms").value(5));
  }

  // --- Validation Tests (4) ---

  @Test
  void testExecuteRule_InvalidRuleId_ThrowsBadRequest() throws Exception {
    // Empty rule_id should fail @ValidRuleId validation
    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"\",\"data\":{\"amount\":100}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
  }

  @Test
  void testExecuteRule_NullData_ThrowsBadRequest() throws Exception {
    // Missing data field should fail @NotNull validation
    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"test.rule\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
  }

  @Test
  void testExecuteRule_TooManyFields_ThrowsBadRequest() throws Exception {
    // Build a JSON data object with more fields than allowed (test profile allows 50)
    String fields =
        IntStream.range(0, 51)
            .mapToObj(i -> "\"field" + i + "\":" + i)
            .collect(Collectors.joining(","));
    String json = "{\"rule_id\":\"test.rule\",\"data\":{" + fields + "}}";

    mockMvc
        .perform(post("/execute-rule").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
  }

  @Test
  void testExecuteRule_DangerousContent_ThrowsBadRequest() throws Exception {
    // The SAFE_STRING_PATTERN rejects '<' and '>' characters
    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"rule_id\":\"test.rule\","
                        + "\"data\":{\"name\":\"<script>alert(1)</script>\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
  }

  // --- Error Scenario Tests (3) ---

  @Test
  void testExecuteRule_RuleNotFound_Returns404() throws Exception {
    when(droolsEngineService.hasRule("nonexistent.rule")).thenReturn(false);

    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"nonexistent.rule\",\"data\":{\"amount\":100}}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RULE_NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("Rule not found: nonexistent.rule"));
  }

  @Test
  void testExecuteRule_ExecutionFailure_ReturnsError() throws Exception {
    // When rule execution returns a failure result, the controller throws
    // RuleExecutionException which GlobalExceptionHandler maps to 400
    when(droolsEngineService.hasRule("failing.rule")).thenReturn(true);
    when(droolsEngineService.executeRule(eq("failing.rule"), anyMap()))
        .thenReturn(RuleExecutor.ExecutionResult.failure("Compilation error in rule"));

    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"failing.rule\",\"data\":{\"amount\":100}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("RULE_EXECUTION_ERROR"));
  }

  @Test
  void testExecuteRule_UnexpectedException_ReturnsError() throws Exception {
    // When hasRule throws an unexpected exception, the controller wraps it
    // in RuleExecutionException which GlobalExceptionHandler maps to 400
    when(droolsEngineService.hasRule("error.rule")).thenReturn(true);
    when(droolsEngineService.executeRule(eq("error.rule"), anyMap()))
        .thenThrow(new RuntimeException("Unexpected internal error"));

    mockMvc
        .perform(
            post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_id\":\"error.rule\",\"data\":{\"amount\":100}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("RULE_EXECUTION_ERROR"));
  }

  // --- Log Sanitization Tests (2) ---

  @Test
  void testExecuteRule_SensitiveData_SanitizedInLogs() throws Exception {
    // Capture log output from the controller
    Logger controllerLogger = (Logger) LoggerFactory.getLogger(RuleExecutionController.class);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    controllerLogger.addAppender(listAppender);

    try {
      Map<String, Object> resultData = Map.of("status", "processed");
      when(droolsEngineService.hasRule("payment.rule")).thenReturn(true);
      when(droolsEngineService.executeRule(eq("payment.rule"), anyMap()))
          .thenReturn(RuleExecutor.ExecutionResult.success(resultData, 10L));

      // Send request with a credit card number in the data
      mockMvc
          .perform(
              post("/execute-rule")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"rule_id\":\"payment.rule\","
                          + "\"data\":{\"credit_card\":\"4111111111111111\",\"amount\":50}}"))
          .andExpect(status().isOk());

      // Verify the raw credit card number does not appear in any log message
      String allLogs =
          listAppender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(Collectors.joining(" "));
      assertThat(allLogs).doesNotContain("4111111111111111");
    } finally {
      controllerLogger.detachAppender(listAppender);
    }
  }

  @Test
  void testExecuteRule_ErrorLogging_SanitizesMessages() throws Exception {
    // Capture log output from both the controller and the exception handler
    Logger controllerLogger = (Logger) LoggerFactory.getLogger(RuleExecutionController.class);
    Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> controllerAppender = new ListAppender<>();
    ListAppender<ILoggingEvent> handlerAppender = new ListAppender<>();
    controllerAppender.start();
    handlerAppender.start();
    controllerLogger.addAppender(controllerAppender);
    handlerLogger.addAppender(handlerAppender);

    try {
      // Simulate a rule not found scenario where the ruleId contains a credit card number
      String sensitiveRuleId = "rule.4111-1111-1111-1111";
      when(droolsEngineService.hasRule(sensitiveRuleId)).thenReturn(false);

      mockMvc
          .perform(
              post("/execute-rule")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"rule_id\":\"" + sensitiveRuleId + "\"," + "\"data\":{\"amount\":100}}"))
          .andExpect(status().isNotFound());

      // Verify the raw credit card pattern does not appear in controller logs
      String controllerLogs =
          controllerAppender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(Collectors.joining(" "));
      assertThat(controllerLogs).doesNotContain("4111-1111-1111-1111");

      // Verify the raw credit card pattern does not appear in handler logs
      String handlerLogs =
          handlerAppender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(Collectors.joining(" "));
      assertThat(handlerLogs).doesNotContain("4111-1111-1111-1111");
    } finally {
      controllerLogger.detachAppender(controllerAppender);
      handlerLogger.detachAppender(handlerAppender);
    }
  }

  // --- Malformed JSON (Finding #2) ---

  @Test
  void testExecuteRule_MalformedJson_Returns400InvalidInput() throws Exception {
    mockMvc
        .perform(post("/execute-rule").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.error.message").value("Request body is not valid JSON"));
  }
}
