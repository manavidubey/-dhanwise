package dev.subscout.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void storesMinorUnitsWithCurrencyExponent() {
        Money money = Money.ofMinor(1999, "USD");
        assertEquals(19.99, money.major().doubleValue(), 0.0001);
        assertEquals(2, money.exponent());
    }

    @Test
    void parsesThousandsSeparatorsAndCurrencyNoise() {
        assertEquals(129950, Money.parse("Rs. 1,299.50", "INR").minor());
        assertEquals(-4500, Money.parse("-45.00", "USD").minor());
    }

    @Test
    void respectsZeroDecimalCurrencies() {
        Money yen = Money.parse("1200", "JPY");
        assertEquals(1200, yen.minor());
        assertEquals(0, yen.exponent());
        assertEquals(0, new BigDecimal("1200").compareTo(yen.major()));
    }

    @Test
    void respectsThreeDecimalCurrencies() {
        Money dinar = Money.parse("1.234", "KWD");
        assertEquals(1234, dinar.minor());
        assertEquals(3, dinar.exponent());
    }

    @Test
    void roundsHalfEvenWhenScaling() {
        Money price = Money.ofMinor(1999, "USD");
        assertEquals(2359, price.times(new BigDecimal("1.18")).minor());
    }

    @Test
    void refusesToCombineDifferentCurrencies() {
        Money inr = Money.ofMinor(100, "INR");
        Money usd = Money.ofMinor(100, "USD");
        assertThrows(IllegalArgumentException.class, () -> inr.plus(usd));
        assertThrows(IllegalArgumentException.class, () -> inr.compareTo(usd));
    }

    @Test
    void rejectsUnknownCurrency() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(100, "XYZ"));
    }

    @Test
    void annualisesWithoutOverflow() {
        Money monthly = Money.ofMinor(64900, "INR");
        assertEquals(778800, monthly.annualized(12).minor());
    }
}
