package dev.subscout.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An exact monetary amount expressed in minor units of a specific currency.
 *
 * <p>Two rules make this type safe for financial work:
 * the exponent always travels with the amount (JPY has 0 decimals, KWD has 3), and
 * arithmetic between different currencies is rejected rather than silently coerced.
 */
public record Money(long minor, String currency, int exponent) implements Comparable<Money> {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO-4217 code: " + currency);
        }
        if (exponent < 0 || exponent > 6) {
            throw new IllegalArgumentException("Unsupported currency exponent: " + exponent);
        }
    }

    public static Money ofMinor(long minor, String currency) {
        return new Money(minor, currency, Currencies.exponentOf(currency));
    }

    public static Money ofMinor(long minor, String currency, int exponent) {
        return new Money(minor, currency, exponent);
    }

    public static Money zero(String currency) {
        return ofMinor(0, currency);
    }

    /**
     * Parses a decimal string such as "Rs. 1,299.00" into minor units, rounding half-even.
     * Only one numeric token is accepted: "12.34.56" is rejected rather than silently truncated.
     */
    public static Money parse(String raw, String currency) {
        int exponent = Currencies.exponentOf(currency);
        String cleaned = raw == null ? "" : raw.replaceAll("[^0-9.,\\-]", "").replace(",", "");
        Matcher matcher = NUMBER.matcher(cleaned);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Not a monetary amount: " + raw);
        }
        String token = matcher.group();
        if (matcher.find()) {
            throw new IllegalArgumentException("Not a monetary amount: " + raw);
        }
        BigDecimal major = new BigDecimal(token).setScale(exponent, RoundingMode.HALF_EVEN);
        return new Money(major.movePointRight(exponent).longValueExact(), currency, exponent);
    }

    public BigDecimal major() {
        return BigDecimal.valueOf(minor, exponent);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency, exponent);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency, exponent);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency, exponent);
    }

    public Money abs() {
        return minor < 0 ? negate() : this;
    }

    public Money times(BigDecimal factor) {
        BigDecimal scaled = major().multiply(factor).setScale(exponent, RoundingMode.HALF_EVEN);
        return new Money(scaled.movePointRight(exponent).longValueExact(), currency, exponent);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public Money annualized(int occurrencesPerYear) {
        return new Money(Math.multiplyExact(minor, occurrencesPerYear), currency, exponent);
    }

    public String display() {
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
            format.setCurrency(Currency.getInstance(currency));
            format.setMinimumFractionDigits(exponent);
            format.setMaximumFractionDigits(exponent);
            return format.format(major());
        } catch (IllegalArgumentException unsupported) {
            return major().toPlainString() + " " + currency;
        }
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency) || exponent != other.exponent) {
            throw new IllegalArgumentException(
                    "Cannot combine " + currency + " (exp " + exponent + ") with "
                            + other.currency + " (exp " + other.exponent + ")");
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }
}
