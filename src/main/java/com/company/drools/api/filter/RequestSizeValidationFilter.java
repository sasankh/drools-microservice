package com.company.drools.api.filter;

import com.company.drools.config.ValidationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Filter to validate request size limits before processing */
@Component
public class RequestSizeValidationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestSizeValidationFilter.class);

  private final ValidationConfig validationConfig;
  private final ObjectMapper objectMapper;

  public RequestSizeValidationFilter(ValidationConfig validationConfig, ObjectMapper objectMapper) {
    this.validationConfig = validationConfig;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // Only validate POST requests to execute-rule endpoint
    if ("POST".equalsIgnoreCase(request.getMethod())
        && request.getRequestURI().contains("/execute-rule")) {

      // Check Content-Length header
      long contentLength = request.getContentLengthLong();
      if (contentLength > validationConfig.getRequestMaxSizeBytes()) {
        log.warn(
            "Request size {} exceeds maximum allowed size of {} bytes",
            contentLength,
            validationConfig.getRequestMaxSizeBytes());

        writeErrorResponse(
            response,
            "Request size exceeds maximum limit",
            "Request size: "
                + contentLength
                + " bytes, Maximum allowed: "
                + validationConfig.getRequestMaxSizeBytes()
                + " bytes");
        return;
      }

      // When Content-Length is missing (chunked transfer), wrap the stream to enforce the limit
      if (contentLength == -1) {
        log.debug(
            "Content-Length header missing for request to {}, wrapping with size limit",
            request.getRequestURI());
        HttpServletRequest wrappedRequest =
            new SizeLimitedRequestWrapper(request, validationConfig.getRequestMaxSizeBytes());
        filterChain.doFilter(wrappedRequest, response);
        return;
      }
    }

    // Continue with the filter chain
    filterChain.doFilter(request, response);
  }

  private void writeErrorResponse(HttpServletResponse response, String message, String details)
      throws IOException {
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("rule_id", null);
    errorResponse.put("result", null);

    Map<String, Object> error = new HashMap<>();
    error.put("code", "REQUEST_TOO_LARGE");
    error.put("message", message);
    error.put("details", details);
    error.put("timestamp", java.time.Instant.now().toString());

    errorResponse.put("error", error);

    String jsonResponse = objectMapper.writeValueAsString(errorResponse);
    response.getWriter().write(jsonResponse);
    response.getWriter().flush();
  }

  /** Wraps a request to enforce a size limit on the input stream when Content-Length is absent. */
  private static class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {
    private final long maxBytes;

    SizeLimitedRequestWrapper(HttpServletRequest request, long maxBytes) {
      super(request);
      this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream original = super.getInputStream();
      return new SizeLimitedInputStream(original, maxBytes);
    }
  }

  /** ServletInputStream that throws IOException when the size limit is exceeded. */
  private static class SizeLimitedInputStream extends ServletInputStream {
    private final ServletInputStream delegate;
    private final long maxBytes;
    private long bytesRead = 0;

    SizeLimitedInputStream(ServletInputStream delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b != -1) {
        bytesRead++;
        checkLimit();
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int count = delegate.read(b, off, len);
      if (count > 0) {
        bytesRead += count;
        checkLimit();
      }
      return count;
    }

    private void checkLimit() throws IOException {
      if (bytesRead > maxBytes) {
        throw new IOException("Request body exceeds maximum size of " + maxBytes + " bytes");
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      delegate.setReadListener(readListener);
    }
  }
}
