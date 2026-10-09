package com.stockforge.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkuTest {

    @ParameterizedTest
    @ValueSource(strings = {"ABC", "SNEAKER-RED-42", "0-1", "A--"})
    void acceptsWellFormedSkus(String value) {
        assertThat(Sku.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-ABC", "abc", "ABC DEF", "ABC_DEF", "ÄBC", " ABC"})
    void rejectsMalformedSkus(String value) {
        assertThatThrownBy(() -> Sku.of(value)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void enforcesLengthBetween3And64Characters() {
        assertThatThrownBy(() -> Sku.of("AB")).isInstanceOf(DomainValidationException.class);
        assertThat(Sku.of("ABC")).isNotNull();
        assertThat(Sku.of("A".repeat(64))).isNotNull();
        assertThatThrownBy(() -> Sku.of("A".repeat(65))).isInstanceOf(DomainValidationException.class);
    }
}
