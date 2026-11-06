package dev.subscout.insight;

import dev.subscout.platform.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Attributes the change between two card charges to its actual causes.
 *
 * <p>Telescoping decomposition in settled-currency terms:
 * <pre>
 *   dp = (b2 - b1) * r2 * m2              list price / tier / quantity
 *   df = b1 * (r2 - r1) * m2              reference FX rate
 *   dm = b1 * r1 * (m2 - m1)              card FX markup
 *   residual = actual - (dp + df + dm)    unexplained: fees, tax, proration, unknown
 * </pre>
 * The residual is never forced to zero. An unexplained amount is a real signal.
 */
public final class FxDecomposer {

    public record Charge(
            LocalDate on,
            Money billed,
            Money settled,
            BigDecimal referenceRate,
            int markupBps) {
    }

    public record Decomposition(
            LocalDate from,
            LocalDate to,
            Money totalChange,
            Money listPriceEffect,
            Money fxReferenceEffect,
            Money fxMarkupEffect,
            Money unexplained,
            boolean fullyExplained) {
    }

    public Decomposition decompose(Charge previous, Charge current) {
        requireCompatible(previous, current);

        BigDecimal b1 = previous.billed().major();
        BigDecimal b2 = current.billed().major();
        BigDecimal r1 = previous.referenceRate();
        BigDecimal r2 = current.referenceRate();
        BigDecimal m1 = markupFactor(previous.markupBps());
        BigDecimal m2 = markupFactor(current.markupBps());

        BigDecimal priceEffect = b2.subtract(b1).multiply(r2).multiply(m2);
        BigDecimal fxReferenceEffect = b1.multiply(r2.subtract(r1)).multiply(m2);
        BigDecimal fxMarkupEffect = b1.multiply(r1).multiply(m2.subtract(m1));
        BigDecimal explained = priceEffect.add(fxReferenceEffect).add(fxMarkupEffect);

        BigDecimal totalChange = current.settled().major().subtract(previous.settled().major());
        BigDecimal unexplained = totalChange.subtract(explained);

        Money template = current.settled();
        long tolerance = Math.max(1, Math.abs(totalChange.movePointRight(template.exponent()).longValue()) / 100);

        return new Decomposition(
                previous.on(),
                current.on(),
                toMoney(totalChange, template),
                toMoney(priceEffect, template),
                toMoney(fxReferenceEffect, template),
                toMoney(fxMarkupEffect, template),
                toMoney(unexplained, template),
                Math.abs(toMoney(unexplained, template).minor()) <= tolerance);
    }

    private static void requireCompatible(Charge previous, Charge current) {
        if (!previous.settled().currency().equals(current.settled().currency())
                || previous.settled().exponent() != current.settled().exponent()) {
            throw new IllegalArgumentException("Charges settle in different currencies");
        }
        if (!previous.billed().currency().equals(current.billed().currency())) {
            throw new IllegalArgumentException("Charges are billed in different currencies");
        }
    }

    private static BigDecimal markupFactor(int basisPoints) {
        return BigDecimal.ONE.add(BigDecimal.valueOf(basisPoints).movePointLeft(4));
    }

    private static Money toMoney(BigDecimal major, Money template) {
        BigDecimal scaled = major.setScale(template.exponent(), RoundingMode.HALF_EVEN);
        return new Money(scaled.movePointRight(template.exponent()).longValueExact(),
                template.currency(), template.exponent());
    }
}
