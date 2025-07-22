package com.company.drools.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Validation annotation for rule IDs */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RuleIdValidator.class)
@Documented
public @interface ValidRuleId {
  String message() default "Invalid rule ID format";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
