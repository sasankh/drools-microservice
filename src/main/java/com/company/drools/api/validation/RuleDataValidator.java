package com.company.drools.api.validation;

import com.company.drools.config.ValidationConfig;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;

/** Validator for rule execution data payload */
public class RuleDataValidator implements ConstraintValidator<ValidRuleData, Map<String, Object>> {

  // Pattern for safe string values (no script injection)
  private static final Pattern SAFE_STRING_PATTERN = Pattern.compile("^[^<>\"';&|]*$");

  // Dangerous patterns that might indicate injection attempts
  private static final Pattern[] DANGEROUS_PATTERNS = {
    Pattern.compile("(?i)script", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)javascript", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)eval\\s*\\(", Pattern.CASE_INSENSITIVE),
    Pattern.compile("(?i)exec\\s*\\(", Pattern.CASE_INSENSITIVE)
  };

  @Autowired private ValidationConfig validationConfig;

  @Override
  public void initialize(ValidRuleData constraintAnnotation) {
    // No initialization needed
  }

  @Override
  public boolean isValid(Map<String, Object> data, ConstraintValidatorContext context) {
    if (data == null) {
      addViolation(context, "Rule data cannot be null");
      return false;
    }

    // Check number of fields
    if (data.size() > validationConfig.getDataMaxFields()) {
      addViolation(
          context, "Too many data fields (max " + validationConfig.getDataMaxFields() + ")");
      return false;
    }

    // Validate each field
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      // Validate key
      if (!isValidKey(key, context)) {
        return false;
      }

      // Validate value
      if (!isValidValue(key, value, context)) {
        return false;
      }
    }

    return true;
  }

  private boolean isValidKey(String key, ConstraintValidatorContext context) {
    if (key == null || key.trim().isEmpty()) {
      addViolation(context, "Data keys cannot be empty");
      return false;
    }

    if (key.length() > 100) {
      addViolation(context, "Data key too long: " + key);
      return false;
    }

    // Check for dangerous patterns in keys
    if (containsDangerousPattern(key)) {
      addViolation(context, "Data key contains potentially dangerous content: " + key);
      return false;
    }

    return true;
  }

  private boolean isValidValue(String key, Object value, ConstraintValidatorContext context) {
    if (value == null) {
      return true; // Null values are allowed
    }

    // String validation
    if (value instanceof String) {
      String strValue = (String) value;

      if (strValue.length() > validationConfig.getDataMaxStringLength()) {
        addViolation(
            context,
            "String value too long for key '"
                + key
                + "' (max "
                + validationConfig.getDataMaxStringLength()
                + " characters)");
        return false;
      }

      if (!SAFE_STRING_PATTERN.matcher(strValue).matches()) {
        addViolation(context, "String value contains unsafe characters for key '" + key + "'");
        return false;
      }

      if (containsDangerousPattern(strValue)) {
        addViolation(
            context, "String value contains potentially dangerous content for key '" + key + "'");
        return false;
      }
    }
    // Number validation
    else if (value instanceof Number) {
      Number numValue = (Number) value;

      if (Math.abs(numValue.longValue()) > validationConfig.getDataMaxNumberValue()) {
        addViolation(
            context,
            "Number value too large for key '"
                + key
                + "' (max "
                + validationConfig.getDataMaxNumberValue()
                + ")");
        return false;
      }
    }
    // Boolean is always safe
    else if (value instanceof Boolean) {
      // Boolean values are safe
    }
    // Collections and other types
    else {
      // For safety, convert to string and validate
      String strValue = value.toString();
      if (strValue.length() > validationConfig.getDataMaxStringLength()) {
        addViolation(context, "Value too long when converted to string for key '" + key + "'");
        return false;
      }

      if (containsDangerousPattern(strValue)) {
        addViolation(context, "Value contains potentially dangerous content for key '" + key + "'");
        return false;
      }
    }

    return true;
  }

  private boolean containsDangerousPattern(String value) {
    for (Pattern pattern : DANGEROUS_PATTERNS) {
      if (pattern.matcher(value).find()) {
        return true;
      }
    }
    return false;
  }

  private void addViolation(ConstraintValidatorContext context, String message) {
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
  }
}
