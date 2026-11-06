package dev.subscout.insight;

import dev.subscout.platform.Money;
import java.util.List;
import java.util.Locale;

/**
 * Savings are classified, never summed across classes. "You could save" and "you did save"
 * are different claims, and conflating them is how finance apps lose credibility.
 */
public final class SavingsLedger {

    public enum Kind {
        POTENTIAL,
        ESTIMATED,
        CONFIRMED,
        REALIZED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromWire(String value) {
            for (Kind kind : values()) {
                if (kind.wire().equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown savings class: " + value);
        }
    }

    public record Entry(Kind kind, Money amount, String evidence) {
    }

    public record Totals(
            String currency,
            int exponent,
            Money potential,
            Money estimated,
            Money confirmed,
            Money realized) {
    }

    public Totals totals(String currency, List<Entry> entries) {
        Money potential = Money.zero(currency);
        Money estimated = Money.zero(currency);
        Money confirmed = Money.zero(currency);
        Money realized = Money.zero(currency);

        for (Entry entry : entries) {
            if (!entry.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Savings ledger is single-currency per view: " + currency + " vs " + entry.amount().currency());
            }
            switch (entry.kind()) {
                case POTENTIAL -> potential = potential.plus(entry.amount());
                case ESTIMATED -> estimated = estimated.plus(entry.amount());
                case CONFIRMED -> confirmed = confirmed.plus(entry.amount());
                case REALIZED -> realized = realized.plus(entry.amount());
            }
        }
        int exponent = entries.isEmpty() ? dev.subscout.platform.Currencies.exponentOf(currency) : entries.get(0).amount().exponent();
        return new Totals(currency, exponent, potential, estimated, confirmed, realized);
    }
}
