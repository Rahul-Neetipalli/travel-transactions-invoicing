package com.navan.billing.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Money is BigDecimal only. Scale is the ISO 4217 exponent of the currency; nothing is ever rounded. */
public final class MoneyRules {

    private static final int MAX_INTEGER_DIGITS = 15;   // NUMERIC(19,4)
    private static final int RATE_MAX_SCALE = 6;         // NUMERIC(9,6)
    private static final int RATE_MAX_INTEGER_DIGITS = 3;

    private MoneyRules() {
    }

    /** Uppercase ISO 4217 code with a real minor-unit exponent, otherwise null. */
    public static Currency parseCurrency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            return null;
        }
        try {
            Currency currency = Currency.getInstance(code);
            return currency.getDefaultFractionDigits() >= 0 ? currency : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int exponent(String currencyCode) {
        return Currency.getInstance(currencyCode).getDefaultFractionDigits();
    }

    /** Null when the amount is acceptable for the currency, otherwise the reason. Never rounds. */
    public static String amountProblem(BigDecimal value, Currency currency) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (currency != null && stripped.scale() > currency.getDefaultFractionDigits()) {
            return "has too many decimal places for " + currency.getCurrencyCode()
                    + " (at most " + currency.getDefaultFractionDigits() + ")";
        }
        if (integerDigits(stripped) > MAX_INTEGER_DIGITS) {
            return "is too large";
        }
        return null;
    }

    public static String rateProblem(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > RATE_MAX_SCALE) {
            return "has too many decimal places (at most " + RATE_MAX_SCALE + ")";
        }
        if (integerDigits(stripped) > RATE_MAX_INTEGER_DIGITS) {
            return "is too large";
        }
        return null;
    }

    /** Output scale = currency exponent. Throws if a non-zero digit would be lost. */
    public static BigDecimal forOutput(BigDecimal value, String currencyCode) {
        return value.setScale(exponent(currencyCode), RoundingMode.UNNECESSARY);
    }

    /** Rates are informational; print without the storage padding (0.070000 -> 0.07). */
    public static BigDecimal rateForOutput(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }

    private static int integerDigits(BigDecimal stripped) {
        return stripped.signum() == 0 ? 0 : Math.max(stripped.precision() - stripped.scale(), 0);
    }
}
