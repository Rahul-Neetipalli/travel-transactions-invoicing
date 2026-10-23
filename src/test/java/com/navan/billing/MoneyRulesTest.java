package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navan.billing.common.MoneyRules;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Money rules in isolation: exact arithmetic, ISO currencies, per-currency scale, no rounding. */
class MoneyRulesTest {

    /** Why we never use double: 0.1 + 0.2 is not 0.3 in floating point, but is in BigDecimal. */
    @Test
    void sumsAreExactWhereDoublesAreNot() {
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
        assertThat(new BigDecimal("0.1").add(new BigDecimal("0.2"))).isEqualByComparingTo("0.3");
    }

    /** Only real, uppercase ISO 4217 codes are currencies. */
    @Test
    void currencyMustBeAnUppercaseIsoCode() {
        assertThat(MoneyRules.parseCurrency("EUR")).isNotNull();
        assertThat(MoneyRules.parseCurrency("JPY")).isNotNull();
        assertThat(MoneyRules.parseCurrency("eur")).isNull();
        assertThat(MoneyRules.parseCurrency("EURO")).isNull();
        assertThat(MoneyRules.parseCurrency("ZZZ")).isNull();
        assertThat(MoneyRules.parseCurrency(null)).isNull();
    }

    /** Decimal places are checked per currency (EUR 2, JPY 0, KWD 3); trailing zeros are harmless. */
    @Test
    void amountsMayNotExceedTheCurrencyExponent() {
        var eur = MoneyRules.parseCurrency("EUR");
        var jpy = MoneyRules.parseCurrency("JPY");
        var kwd = MoneyRules.parseCurrency("KWD");

        assertThat(MoneyRules.amountProblem(new BigDecimal("10.50"), eur)).isNull();
        assertThat(MoneyRules.amountProblem(new BigDecimal("10.500"), eur)).as("trailing zeros are fine").isNull();
        assertThat(MoneyRules.amountProblem(new BigDecimal("10.005"), eur)).isNotNull();
        assertThat(MoneyRules.amountProblem(new BigDecimal("100.5"), jpy)).isNotNull();
        assertThat(MoneyRules.amountProblem(new BigDecimal("100"), jpy)).isNull();
        assertThat(MoneyRules.amountProblem(new BigDecimal("1.005"), kwd)).isNull();
        assertThat(MoneyRules.amountProblem(new BigDecimal("1E+20"), eur)).as("does not fit NUMERIC(19,4)").isNotNull();
    }

    /** Output uses the currency's scale; if a digit would be lost we fail loudly instead of rounding. */
    @Test
    void outputIsScaledToTheCurrencyAndNeverRounds() {
        assertThat(MoneyRules.forOutput(new BigDecimal("220.0000"), "EUR").toPlainString()).isEqualTo("220.00");
        assertThat(MoneyRules.forOutput(new BigDecimal("500.0000"), "JPY").toPlainString()).isEqualTo("500");
        assertThatThrownBy(() -> MoneyRules.forOutput(new BigDecimal("10.0050"), "EUR")).isInstanceOf(ArithmeticException.class);
    }

    /** Tax rates are stored as NUMERIC(9,6) but printed without padding (0.070000 -> 0.07). */
    @Test
    void ratesPrintWithoutStoragePadding() {
        assertThat(MoneyRules.rateForOutput(new BigDecimal("0.070000")).toPlainString()).isEqualTo("0.07");
        assertThat(MoneyRules.rateForOutput(new BigDecimal("1.000000")).toPlainString()).isEqualTo("1");
        assertThat(MoneyRules.rateForOutput(null)).isNull();
    }
}
