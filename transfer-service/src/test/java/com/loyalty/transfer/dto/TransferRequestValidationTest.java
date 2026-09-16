package com.loyalty.transfer.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRequestValidationTest {

    private final Validator validator;

    TransferRequestValidationTest() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    void rejectsNonPositiveAmount() {
        TransferRequest request = new TransferRequest();
        request.setSourceAccountId("acc-1");
        request.setTargetAccountId("acc-2");
        request.setAmount(0L);

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void acceptsValidRequest() {
        TransferRequest request = new TransferRequest();
        request.setSourceAccountId("acc-1");
        request.setTargetAccountId("acc-2");
        request.setAmount(50L);

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}
