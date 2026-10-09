package com.stockforge.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({"19.99, USD, 19.99", "20, USD, 20.00", "19.9900, USD, 19.99", "1500, JPY, 1500", "0, EUR, 0.00"})
    void normalizesToTheCurrencyMinorUnitScale(String amount, String currency, String expected) {
        Money money = Money.of(amount, currency);

        assertThat(money.amount()).isEqualTo(new BigDecimal(expected));
        assertThat(money.currency()).isEqualTo(Currency.getInstance(currency));
    }

    @Test
    void rejectsMorePrecisionThanTheCurrencyAllowsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.of("19.999", "USD"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("at most 2 decimal places");
        assertThatThrownBy(() -> Money.of("1500.5", "JPY")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.of("-0.01", "USD")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsAmountsThatDoNotFitTheDatabaseColumn() {
        assertThat(Money.of("999999999999999.99", "USD").amount()).isEqualByComparingTo("999999999999999.99");
        assertThatThrownBy(() -> Money.of("1000000000000000", "USD"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Amount is too large");
    }

    @Test
    void rejectsUnknownAndUnsupportedCurrencies() {
        assertThatThrownBy(() -> Money.of("1.00", "ABC")).isInstanceOf(DomainValidationException.class);
        // XXX is a valid ISO 4217 code ("no currency") without a minor unit.
        assertThatThrownBy(() -> Money.of("1.00", "XXX")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNonNumericAmounts() {
        assertThatThrownBy(() -> Money.of("ten", "USD")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equalAmountsWithDifferentInputScalesAreEqual() {
        assertThat(Money.of("5", "USD")).isEqualTo(Money.of("5.00", "USD"));
    }
}
