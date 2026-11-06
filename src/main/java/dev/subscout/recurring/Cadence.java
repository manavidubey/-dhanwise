package dev.subscout.recurring;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Billing cadence, including the cases that break naive day-count maths:
 * 28-day cycles, month-end clamping (31 Jan -> 28 Feb -> 31 Mar), and annual billing.
 */
public enum Cadence {

    WEEKLY("weekly", 52),
    BIWEEKLY("biweekly", 26),
    FOUR_WEEKLY("four_weekly", 13),
    MONTHLY("monthly", 12),
    QUARTERLY("quarterly", 4),
    SEMIANNUAL("semiannual", 2),
    ANNUAL("annual", 1),
    VARIABLE("variable", 0);

    private final String wire;
    private final int occurrencesPerYear;

    Cadence(String wire, int occurrencesPerYear) {
        this.wire = wire;
        this.occurrencesPerYear = occurrencesPerYear;
    }

    public String wire() {
        return wire;
    }

    public int occurrencesPerYear() {
        return occurrencesPerYear;
    }

    public static Cadence fromWire(String value) {
        for (Cadence cadence : values()) {
            if (cadence.wire.equalsIgnoreCase(value)) {
                return cadence;
            }
        }
        throw new IllegalArgumentException("Unknown cadence: " + value);
    }

    public LocalDate next(LocalDate from) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(1);
            case BIWEEKLY -> from.plusWeeks(2);
            case FOUR_WEEKLY -> from.plusWeeks(4);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case SEMIANNUAL -> from.plusMonths(6);
            case ANNUAL -> from.plusYears(1);
            case VARIABLE -> from;
        };
    }

    /** Whether the observed gap between two charges is consistent with this cadence. */
    public boolean fits(LocalDate previous, LocalDate next) {
        long days = ChronoUnit.DAYS.between(previous, next);
        long months = ChronoUnit.MONTHS.between(YearMonth.from(previous), YearMonth.from(next));
        return switch (this) {
            case WEEKLY -> Math.abs(days - 7) <= 2;
            case BIWEEKLY -> Math.abs(days - 14) <= 3;
            case FOUR_WEEKLY -> Math.abs(days - 28) <= 1;
            case MONTHLY -> months == 1;
            case QUARTERLY -> months == 3;
            case SEMIANNUAL -> months == 6;
            case ANNUAL -> months == 12;
            case VARIABLE -> false;
        };
    }

    /**
     * Classifies a date sequence. Monthly and four-weekly are separated by calendar
     * behaviour rather than by day count: a 28-day cycle drifts across month boundaries,
     * while monthly billing keeps a stable day-of-month.
     */
    public static Cadence classify(List<LocalDate> dates) {
        if (dates.size() < 2) {
            return VARIABLE;
        }
        List<Long> gaps = gaps(dates);
        long median = median(gaps);

        Cadence candidate;
        if (median >= 340 && median <= 390) {
            candidate = ANNUAL;
        } else if (median >= 170 && median <= 198) {
            candidate = SEMIANNUAL;
        } else if (median >= 80 && median <= 100) {
            candidate = QUARTERLY;
        } else if (median >= 25 && median <= 35) {
            double monthly = fractionMonthStep(dates, 1);
            double fourWeekly = fractionWithin(gaps, 28, 1);
            candidate = fourWeekly > monthly ? FOUR_WEEKLY : MONTHLY;
        } else if (median >= 11 && median <= 18) {
            candidate = BIWEEKLY;
        } else if (median >= 5 && median <= 9) {
            candidate = WEEKLY;
        } else {
            return VARIABLE;
        }

        // A median inside a band is not enough: a 5-day gap followed by two ~40-day gaps
        // has a median of 33 but is not monthly billing.
        int consistent = 0;
        for (int i = 1; i < dates.size(); i++) {
            if (candidate.fits(dates.get(i - 1), dates.get(i))) {
                consistent++;
            }
        }
        double required = gaps.size() <= 3 ? 0.8 : 0.6;
        return consistent / (double) gaps.size() >= required ? candidate : VARIABLE;
    }

    public static Optional<Cadence> parse(String value) {
        for (Cadence cadence : values()) {
            if (cadence.wire.equals(value.toLowerCase(Locale.ROOT))) {
                return Optional.of(cadence);
            }
        }
        return Optional.empty();
    }

    static List<Long> gaps(List<LocalDate> dates) {
        List<Long> gaps = new ArrayList<>(dates.size() - 1);
        for (int i = 1; i < dates.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }
        return gaps;
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static double fractionMonthStep(List<LocalDate> dates, int step) {
        int matches = 0;
        for (int i = 1; i < dates.size(); i++) {
            long diff = ChronoUnit.MONTHS.between(YearMonth.from(dates.get(i - 1)), YearMonth.from(dates.get(i)));
            if (diff == step) {
                matches++;
            }
        }
        return matches / (double) (dates.size() - 1);
    }

    private static double fractionWithin(List<Long> gaps, long target, long tolerance) {
        int matches = 0;
        for (long gap : gaps) {
            if (Math.abs(gap - target) <= tolerance) {
                matches++;
            }
        }
        return matches / (double) gaps.size();
    }
}
