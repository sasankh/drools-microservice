package com.company.drools.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Utility class for sanitizing log messages to prevent sensitive data exposure */
public class LogSanitizer {

  // Common patterns for sensitive data
  private static final Pattern[] SENSITIVE_PATTERNS = {
    Pattern.compile("(?i)password", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)secret", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)token", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)apikey", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)api_key", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)authorization", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)auth", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)credential", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)ssn", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)social.*security", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)credit.*card", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)card.*number", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)cvv", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)pin", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)account.*number", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)routing.*number", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)bank.*account", Pattern.CASE_INSENSITIVE)
  };

  // Common sensitive field names
  private static final Set<String> SENSITIVE_KEYS =
      new HashSet<>(
          Arrays.asList(
              "password",
              "secret",
              "token",
              "apikey",
              "api_key",
              "authorization",
              "auth",
              "credential",
              "ssn",
              "social_security_number",
              "credit_card",
              "card_number",
              "cvv",
              "pin",
              "account_number",
              "routing_number",
              "bank_account",
              "access_token",
              "refresh_token",
              "jwt",
              "bearer_token",
              "private_key",
              "client_secret",
              "session_id",
              "csrf_token",
              "nonce"));

  private static final String REDACTED_VALUE = "[REDACTED]";

  private LogSanitizer() {
    // Utility class - private constructor
  }

  /**
   * Sanitizes a map of data by removing/masking sensitive values
   *
   * @param data The data map to sanitize
   * @return A sanitized copy of the data map
   */
  public static Map<String, Object> sanitizeDataMap(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return data;
    }

    return data.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> sanitizeValue(entry.getKey(), entry.getValue())));
  }

  /**
   * Sanitizes a value based on its key name
   *
   * @param key The field key/name
   * @param value The value to potentially sanitize
   * @return The original value or a redacted version
   */
  public static Object sanitizeValue(String key, Object value) {
    if (key == null || value == null) {
      return value;
    }

    String keyLower = key.toLowerCase();

    // Check if key matches sensitive patterns
    if (SENSITIVE_KEYS.contains(keyLower) || containsSensitivePattern(keyLower)) {
      return REDACTED_VALUE;
    }

    // Additional checks for string values that might contain sensitive data
    if (value instanceof String) {
      String stringValue = (String) value;

      // Check for potential credit card numbers (simplified pattern)
      if (stringValue.matches("\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}")) {
        return maskCreditCard(stringValue);
      }

      // Check for potential SSN (simplified pattern)
      if (stringValue.matches("\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{4}")) {
        return maskSSN(stringValue);
      }

      // Check for potential email addresses in sensitive contexts
      if (keyLower.contains("email") && stringValue.contains("@")) {
        return maskEmail(stringValue);
      }
    }

    return value;
  }

  /**
   * Sanitizes a string message by removing potential sensitive information
   *
   * @param message The message to sanitize
   * @return Sanitized message
   */
  public static String sanitizeMessage(String message) {
    if (message == null || message.isEmpty()) {
      return message;
    }

    String sanitized = message;

    // Replace potential credit card numbers
    sanitized =
        sanitized.replaceAll(
            "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b", "[CC-REDACTED]");

    // Replace potential SSNs
    sanitized = sanitized.replaceAll("\\b\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{4}\\b", "[SSN-REDACTED]");

    // Replace potential tokens/keys (long alphanumeric strings)
    sanitized = sanitized.replaceAll("\\b[A-Za-z0-9]{20,}\\b", "[TOKEN-REDACTED]");

    return sanitized;
  }

  /**
   * Creates a safe representation of data for logging (limits size and sanitizes)
   *
   * @param data The data to create a safe representation for
   * @return Safe string representation
   */
  public static String safeDataRepresentation(Map<String, Object> data) {
    if (data == null) {
      return "null";
    }

    if (data.isEmpty()) {
      return "{}";
    }

    // Limit the number of fields shown in logs
    int maxFields = 10;
    Map<String, Object> sanitizedData = sanitizeDataMap(data);

    if (sanitizedData.size() <= maxFields) {
      return sanitizedData.keySet().toString();
    } else {
      return sanitizedData.keySet().stream()
              .limit(maxFields)
              .collect(java.util.stream.Collectors.toSet())
          + " (+"
          + (sanitizedData.size() - maxFields)
          + " more fields)";
    }
  }

  private static boolean containsSensitivePattern(String key) {
    for (Pattern pattern : SENSITIVE_PATTERNS) {
      if (pattern.matcher(key).find()) {
        return true;
      }
    }
    return false;
  }

  private static String maskCreditCard(String cardNumber) {
    if (cardNumber.length() >= 4) {
      return "****-****-****-" + cardNumber.substring(cardNumber.length() - 4);
    }
    return REDACTED_VALUE;
  }

  private static String maskSSN(String ssn) {
    if (ssn.length() >= 4) {
      return "***-**-" + ssn.substring(ssn.length() - 4);
    }
    return REDACTED_VALUE;
  }

  private static String maskEmail(String email) {
    int atIndex = email.indexOf('@');
    if (atIndex > 1) {
      return email.charAt(0) + "***" + email.substring(atIndex);
    }
    return REDACTED_VALUE;
  }
}
