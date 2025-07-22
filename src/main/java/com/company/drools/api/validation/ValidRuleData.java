package com.company.drools.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Validation annotation for rule execution data */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RuleDataValidator.class)
@Documented
public @interface ValidRuleData {
  String message() default "Invalid rule data";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
