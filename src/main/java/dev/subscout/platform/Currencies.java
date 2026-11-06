package dev.subscout.platform;

import java.util.Map;
import java.util.Set;

/**
 * ISO-4217 minor-unit exponents. Unknown currencies fail closed: guessing an exponent
 * silently changes amounts by orders of magnitude for JPY, KWD and similar.
 */
public final class Currencies {

    private static final Map<String, Integer> EXPONENTS = Map.ofEntries(
            Map.entry("INR", 2), Map.entry("USD", 2), Map.entry("EUR", 2), Map.entry("GBP", 2),
            Map.entry("AUD", 2), Map.entry("CAD", 2), Map.entry("SGD", 2), Map.entry("AED", 2),
            Map.entry("CHF", 2), Map.entry("HKD", 2), Map.entry("NZD", 2), Map.entry("ZAR", 2),
            Map.entry("BRL", 2), Map.entry("MXN", 2), Map.entry("THB", 2), Map.entry("MYR", 2),
            Map.entry("PHP", 2), Map.entry("PLN", 2), Map.entry("TRY", 2), Map.entry("SEK", 2),
            Map.entry("NOK", 2), Map.entry("DKK", 2), Map.entry("CNY", 2),
            Map.entry("JPY", 0), Map.entry("KRW", 0), Map.entry("VND", 0), Map.entry("IDR", 0),
            Map.entry("CLP", 0), Map.entry("ISK", 0),
            Map.entry("KWD", 3), Map.entry("BHD", 3), Map.entry("OMR", 3), Map.entry("JOD", 3),
            Map.entry("TND", 3), Map.entry("LYD", 3), Map.entry("IQD", 3),
            Map.entry("CLF", 4), Map.entry("UYW", 4));

    private Currencies() {
    }

    public static int exponentOf(String code) {
        Integer exponent = EXPONENTS.get(code);
        if (exponent == null) {
            throw new IllegalArgumentException("Unsupported currency: " + code);
        }
        return exponent;
    }

    public static boolean supports(String code) {
        return code != null && EXPONENTS.containsKey(code);
    }

    public static Set<String> supported() {
        return EXPONENTS.keySet();
    }
}
