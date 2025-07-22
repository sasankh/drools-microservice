package com.company.drools.api.validation;

import com.company.drools.config.ValidationConfig;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.regex.Pattern;

/**
 * Validator for rule ID format and length
 */
public class RuleIdValidator implements ConstraintValidator<ValidRuleId, String> {

    // Pattern for valid rule IDs: alphanumeric, dots, dashes, underscores
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    @Autowired
    private ValidationConfig validationConfig;

    @Override
    public void initialize(ValidRuleId constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(String ruleId, ConstraintValidatorContext context) {
        if (ruleId == null || ruleId.trim().isEmpty()) {
            addViolation(context, "Rule ID cannot be empty");
            return false;
        }

        String trimmedRuleId = ruleId.trim();

        // Check length
        if (trimmedRuleId.length() > validationConfig.getRuleIdMaxLength()) {
            addViolation(context, "Rule ID too long (max " + validationConfig.getRuleIdMaxLength() + " characters)");
            return false;
        }

        // Check format
        if (!RULE_ID_PATTERN.matcher(trimmedRuleId).matches()) {
            addViolation(context, "Rule ID contains invalid characters (only alphanumeric, dots, dashes, underscores allowed)");
            return false;
        }

        // Prevent path traversal attempts
        if (trimmedRuleId.contains("..") || trimmedRuleId.startsWith("/") || trimmedRuleId.contains("\\")) {
            addViolation(context, "Rule ID contains invalid path characters");
            return false;
        }

        return true;
    }

    private void addViolation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }
}