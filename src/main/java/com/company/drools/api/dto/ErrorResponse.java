package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

public class ErrorResponse {

  @JsonProperty("code")
  private String code;

  @JsonProperty("message")
  private String message;

  @JsonProperty("details")
  private String details;

  @JsonProperty("timestamp")
  private String timestamp;

  public ErrorResponse() {
    this.timestamp = Instant.now().toString();
  }

  public ErrorResponse(String code, String message, String details) {
    this.code = code;
    this.message = message;
    this.details = details;
    this.timestamp = Instant.now().toString();
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ErrorResponse that = (ErrorResponse) o;
    return Objects.equals(code, that.code)
        && Objects.equals(message, that.message)
        && Objects.equals(details, that.details);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, message, details);
  }

  @Override
  public String toString() {
    return "ErrorResponse{"
        + "code='"
        + code
        + '\''
        + ", message='"
        + message
        + '\''
        + ", details='"
        + details
        + '\''
        + ", timestamp='"
        + timestamp
        + '\''
        + '}';
  }
}
