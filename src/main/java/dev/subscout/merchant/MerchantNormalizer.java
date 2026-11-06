package dev.subscout.merchant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a raw statement descriptor into a stable key plus a readable hint.
 *
 * <p>Only structural cleaning happens here. It never guesses that two different
 * descriptors are the same product: that is an alias decision, recorded separately and
 * always overridable by the user.
 */
@Component
public final class MerchantNormalizer {

    public record Normalized(String normalized, String displayHint) {
    }

    private static final List<String> PROCESSOR_PREFIXES = List.of(
            "RAZORPAY", "PAYU", "CCAVENUE", "BILLDESK", "STRIPE", "PAYPAL", "GPAY", "GOOGLEPAY",
            "PHONEPE", "PAYTM", "UPI", "AMAZON PAY", "INSTAMOJO", "CASHFREE");

    private static final Set<String> NOISE = Set.of(
            "PVT", "LTD", "LIMITED", "PRIVATE", "IND", "INDIA", "IN", "COM", "WWW",
            "PAYMENT", "PAYMENTS", "SUBSCRIPTION", "RECURRING", "AUTOPAY", "MANDATE",
            "BILL", "ONLINE", "DR", "CR", "SI", "NACH", "ACH", "IMPS", "NEFT", "RTGS",
            "TXN", "TRANSACTION", "REF", "ORDER");

    public Normalized normalize(String raw) {
        String upper = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        String cleaned = upper.replaceAll("[^A-Z0-9]+", " ").trim();

        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : PROCESSOR_PREFIXES) {
                if (cleaned.equals(prefix)) {
                    cleaned = "";
                    stripped = true;
                    break;
                }
                if (cleaned.startsWith(prefix + " ")) {
                    cleaned = cleaned.substring(prefix.length()).trim();
                    stripped = true;
                    break;
                }
            }
        }

        List<String> tokens = new ArrayList<>();
        for (String token : cleaned.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (token.matches(".*\\d{3,}.*")) {
                continue;
            }
            if (NOISE.contains(token)) {
                continue;
            }
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals(token)) {
                continue;
            }
            tokens.add(token);
        }

        if (tokens.isEmpty()) {
            String fallback = cleaned.isBlank() ? "UNKNOWN MERCHANT" : cleaned;
            return new Normalized(fallback, titleCase(fallback));
        }

        String normalized = String.join(" ", tokens);
        String hint = titleCase(String.join(" ", tokens.subList(0, Math.min(2, tokens.size()))));
        return new Normalized(normalized, hint);
    }

    private static String titleCase(String value) {
        String[] parts = value.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
