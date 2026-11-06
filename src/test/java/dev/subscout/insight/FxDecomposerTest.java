package dev.subscout.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.subscout.platform.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FxDecomposerTest {

    private final FxDecomposer decomposer = new FxDecomposer();

    private static FxDecomposer.Charge charge(String day, String billed, String settled, String rate) {
        return new FxDecomposer.Charge(
                LocalDate.parse(day),
                Money.parse(billed, "USD"),
                Money.parse(settled, "INR"),
                new BigDecimal(rate),
                300);
    }

    @Test
    void attributesPriceIncreaseWithStableFx() {
        FxDecomposer.Decomposition result = decomposer.decompose(
                charge("2026-01-10", "10.00", "854.90", "83"),
                charge("2026-02-10", "12.00", "1038.24", "84"));

        assertEquals(18334, result.totalChange().minor());
        assertEquals(17304, result.listPriceEffect().minor());
        assertEquals(1030, result.fxReferenceEffect().minor());
        assertEquals(0, result.fxMarkupEffect().minor());
        assertEquals(0, result.unexplained().minor());
        assertTrue(result.fullyExplained());
    }

    @Test
    void attributesMarkupChangeAlone() {
        FxDecomposer.Charge previous = new FxDecomposer.Charge(
                LocalDate.parse("2026-01-10"), Money.parse("10.00", "USD"),
                Money.parse("854.90", "INR"), new BigDecimal("83"), 300);
        FxDecomposer.Charge current = new FxDecomposer.Charge(
                LocalDate.parse("2026-02-10"), Money.parse("10.00", "USD"),
                Money.parse("859.05", "INR"), new BigDecimal("83"), 350);

        FxDecomposer.Decomposition result = decomposer.decompose(previous, current);
        assertEquals(0, result.listPriceEffect().minor());
        assertEquals(0, result.fxReferenceEffect().minor());
        assertEquals(415, result.fxMarkupEffect().minor());
        assertEquals(0, result.unexplained().minor());
    }

    @Test
    void keepsUnexplainedResidualVisible() {
        FxDecomposer.Decomposition result = decomposer.decompose(
                charge("2026-01-10", "10.00", "854.90", "83"),
                charge("2026-02-10", "12.00", "1060.00", "84"));

        assertEquals(20510, result.totalChange().minor());
        assertEquals(2176, result.unexplained().minor());
        assertFalse(result.fullyExplained());
    }

    @Test
    void handlesZeroDecimalBilledCurrency() {
        FxDecomposer.Charge previous = new FxDecomposer.Charge(
                LocalDate.parse("2026-01-10"), Money.parse("1000", "JPY"),
                Money.parse("560.00", "INR"), new BigDecimal("0.56"), 0);
        FxDecomposer.Charge current = new FxDecomposer.Charge(
                LocalDate.parse("2026-02-10"), Money.parse("1200", "JPY"),
                Money.parse("672.00", "INR"), new BigDecimal("0.56"), 0);

        FxDecomposer.Decomposition result = decomposer.decompose(previous, current);
        assertEquals(11200, result.listPriceEffect().minor());
        assertEquals(0, result.unexplained().minor());
    }

    @Test
    void rejectsChargesSettlingInDifferentCurrencies() {
        FxDecomposer.Charge usdSettled = new FxDecomposer.Charge(
                LocalDate.parse("2026-01-10"), Money.parse("10.00", "USD"),
                Money.parse("10.00", "USD"), new BigDecimal("1"), 0);
        assertThrows(IllegalArgumentException.class,
                () -> decomposer.decompose(charge("2026-01-10", "10.00", "854.90", "83"), usdSettled));
    }
}
