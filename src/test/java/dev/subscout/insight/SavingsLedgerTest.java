package dev.subscout.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.subscout.platform.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class SavingsLedgerTest {

    private final SavingsLedger ledger = new SavingsLedger();

    @Test
    void keepsEachClassSeparate() {
        SavingsLedger.Totals totals = ledger.totals("INR", List.of(
                new SavingsLedger.Entry(SavingsLedger.Kind.POTENTIAL, Money.ofMinor(50000, "INR"), "overlap"),
                new SavingsLedger.Entry(SavingsLedger.Kind.ESTIMATED, Money.ofMinor(20000, "INR"), "price"),
                new SavingsLedger.Entry(SavingsLedger.Kind.CONFIRMED, Money.ofMinor(10000, "INR"), "cancelled"),
                new SavingsLedger.Entry(SavingsLedger.Kind.REALIZED, Money.ofMinor(5000, "INR"), "statement")));

        assertEquals(50000, totals.potential().minor());
        assertEquals(20000, totals.estimated().minor());
        assertEquals(10000, totals.confirmed().minor());
        assertEquals(5000, totals.realized().minor());
    }

    @Test
    void returnsZeroesForEmptyLedger() {
        SavingsLedger.Totals totals = ledger.totals("INR", List.of());
        assertEquals(0, totals.potential().minor());
        assertEquals(0, totals.realized().minor());
    }

    @Test
    void refusesMixedCurrencyLedger() {
        assertThrows(IllegalArgumentException.class, () -> ledger.totals("INR", List.of(
                new SavingsLedger.Entry(SavingsLedger.Kind.ESTIMATED, Money.ofMinor(100, "USD"), "x"))));
    }
}
