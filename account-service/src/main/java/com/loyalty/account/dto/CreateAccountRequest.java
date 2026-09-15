package com.loyalty.account.dto;

import jakarta.validation.constraints.PositiveOrZero;

public class CreateAccountRequest {

    @PositiveOrZero
    private Long balance;

    public Long getBalance() {
        return balance;
    }

    public void setBalance(Long balance) {
        this.balance = balance;
    }
}
