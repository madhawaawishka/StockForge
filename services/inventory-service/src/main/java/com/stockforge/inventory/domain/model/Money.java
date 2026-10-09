package com.stockforge.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * A non-negative monetary amount in a specific currency.
 *
 * <p>The amount is normalized to the currency's minor-unit scale (2 for USD, 0 for JPY). Amounts with more precision
 * than the currency allows are rejected rather than rounded: silently changing a price is a bug.
 */
public record Money(BigDecimal amount, Currency currency) {

    /** Matches the database column NUMERIC(19,4): at most 15 digits before the decimal point. */
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

    /** Parses an amount such as {@code "19.99"} and an ISO 4217 currency code such as {@code "USD"}. */
    public static Money of(String amount, String currencyCode) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        BigDecimal parsedAmount;
        try {
            parsedAmount = new BigDecimal(amount);
        } catch (NumberFormatException notANumber) {
            throw new DomainValidationException("Amount must be a decimal number");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException unknownCode) {
            throw new DomainValidationException("Currency must be an ISO 4217 code");
        }
        return new Money(parsedAmount, currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
