package com.loyalty.account.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateAccountRequestValidationTest {

    private final Validator validator;

    CreateAccountRequestValidationTest() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    void rejectsNegativeBalance() {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setBalance(-10L);

        Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void acceptsNullBalance() {
        CreateAccountRequest request = new CreateAccountRequest();

        Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}
