package com.stockforge.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** Non-negative monetary amount in a specific currency (spec §13). */
public record Money(BigDecimal amount, Currency currency) {

    static final int MAX_INTEGER_DIGITS = 15;

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.signum() < 0) {
            throw new DomainValidationException("Amount must not be negative");
        }
        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new DomainValidationException("Currency " + currency + " is not supported");
        }
        try {
            amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooPrecise) {
            throw new DomainValidationException(
                    "Amount must have at most " + fractionDigits + " decimal places for " + currency);
        }
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new DomainValidationException("Amount is too large");
        }
    }

    public static Money of(String amount, String currencyCode) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        BigDecimal parsedAmount;
        try {
            parsedAmount = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new DomainValidationException("Amount must be a decimal number");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("Currency must be an ISO 4217 code");
        }
        return new Money(parsedAmount, currency);
    }

    public Money multiply(int factor) {
        if (factor < 0) {
            throw new DomainValidationException("Factor must not be negative");
        }
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new DomainValidationException("Cannot add amounts in different currencies");
        }
        return new Money(amount.add(other.amount), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
