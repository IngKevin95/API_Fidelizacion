package com.loyalty.account.dto;

import jakarta.validation.constraints.NotNull;

public class UpdateLimitsRequest {

    @NotNull
    private Long minBalance;

    public Long getMinBalance() {
        return minBalance;
    }

    public void setMinBalance(Long minBalance) {
        this.minBalance = minBalance;
    }
}
