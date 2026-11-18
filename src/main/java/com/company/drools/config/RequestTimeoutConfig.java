package com.company.drools.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers a servlet filter that surfaces the configured HTTP timeout values as response headers
 * and logs slow requests. NOTE: this is observational only — it does NOT enforce/abort a request on
 * timeout. Actual enforcement lives at the rule-execution layer ({@code RuleExecutor} timeout +
 * halt) and the servlet container's connection timeouts.
 */
@Configuration
public class RequestTimeoutConfig {

  /**
   * Registers {@link RequestTimeoutFilter}, which adds informational {@code X-Request-Timeout} /
   * {@code X-Connection-Timeout} headers and logs slow requests. It does not enforce a timeout.
   */
  @Bean
  public FilterRegistrationBean<RequestTimeoutFilter> requestTimeoutFilter(
      TimeoutConfig timeoutConfig) {
    FilterRegistrationBean<RequestTimeoutFilter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(new RequestTimeoutFilter(timeoutConfig));
    registrationBean.addUrlPatterns("/execute-rule", "/admin/*");
    registrationBean.setOrder(1);
    return registrationBean;
  }

  public static class RequestTimeoutFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTimeoutFilter.class);
    private final TimeoutConfig timeoutConfig;

    public RequestTimeoutFilter(TimeoutConfig timeoutConfig) {
      this.timeoutConfig = timeoutConfig;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

      // Set timeout headers
      response.setHeader(
          "X-Request-Timeout", String.valueOf(timeoutConfig.getHttpReadTimeoutSeconds()));
      response.setHeader(
          "X-Connection-Timeout", String.valueOf(timeoutConfig.getHttpConnectionTimeoutSeconds()));

      // Set request start time for timeout tracking
      request.setAttribute("request.start.time", System.currentTimeMillis());

      long startTime = System.currentTimeMillis();

      try {
        filterChain.doFilter(request, response);

        // Log slow requests
        long duration = System.currentTimeMillis() - startTime;
        if (duration > TimeUnit.SECONDS.toMillis(timeoutConfig.getHttpReadTimeoutSeconds() / 2)) {
          log.warn(
              "Slow request detected: {} took {}ms (URI: {})",
              request.getMethod(),
              duration,
              request.getRequestURI());
        }

      } catch (
          Exception e) { // NOSONAR java:S2139 — logs duration+URI before rethrowing; context not
        // available in caller
        long duration = System.currentTimeMillis() - startTime;
        log.error(
            "Request failed after {}ms (URI: {}): {}",
            duration,
            request.getRequestURI(),
            e.getMessage());
        throw e;
      }
    }
  }
}
