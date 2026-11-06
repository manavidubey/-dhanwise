package dev.subscout.ledger;

import dev.subscout.platform.Currencies;
import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses bank/card statement CSV without committing to one bank's layout.
 *
 * <p>Handles preamble lines, quoted fields with embedded commas and newlines, separate
 * debit/credit columns, and per-row currency. Malformed rows are reported with their row
 * number instead of aborting the whole import: a statement with one bad line should still
 * import the other 200.
 */
public final class CsvStatementParser {

    public enum Direction {
        DEBIT,
        CREDIT
    }

    /**
     * How a statement's single amount column expresses direction. Card statements usually
     * show spends as positive; bank statements usually show them as negative. Guessing
     * wrong inverts every recommendation, so the caller states it explicitly.
     */
    public enum SignConvention {
        NEGATIVE_IS_DEBIT,
        POSITIVE_IS_DEBIT;

        Direction resolve(boolean negative) {
            return switch (this) {
                case NEGATIVE_IS_DEBIT -> negative ? Direction.DEBIT : Direction.CREDIT;
                case POSITIVE_IS_DEBIT -> negative ? Direction.CREDIT : Direction.DEBIT;
            };
        }
    }

    /** Amount is always positive; {@link Direction} carries the sign of the movement. */
    public record ParsedRow(
            int line,
            LocalDate postedOn,
            String description,
            Money amount,
            Direction direction,
            String externalRef) {
    }

    public record ParseResult(List<ParsedRow> rows, List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    private static final int MAX_RECORDS = 20_000;
    private static final int HEADER_SCAN_LIMIT = 15;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu/MM/dd"));

    private static final Set<String> DATE_HEADERS = Set.of(
            "date", "posted on", "posted", "transaction date", "txn date", "value date", "date of transaction");
    private static final Set<String> DESC_HEADERS = Set.of(
            "description", "narration", "particulars", "details", "transaction description", "remarks", "merchant");
    private static final Set<String> AMOUNT_HEADERS = Set.of("amount", "transaction amount", "amt");
    private static final Set<String> DEBIT_HEADERS = Set.of("debit", "withdrawal", "withdrawal amt", "dr", "debit amount");
    private static final Set<String> CREDIT_HEADERS = Set.of("credit", "deposit", "deposit amt", "cr", "credit amount");
    private static final Set<String> CURRENCY_HEADERS = Set.of("currency", "ccy");
    private static final Set<String> REF_HEADERS = Set.of(
            "reference", "ref", "ref no", "reference no", "cheque no", "transaction id", "txn id", "utr");

    public ParseResult parse(String content, String defaultCurrency) {
        return parse(content, defaultCurrency, SignConvention.NEGATIVE_IS_DEBIT);
    }

    public ParseResult parse(String content, String defaultCurrency, SignConvention convention) {
        List<String> errors = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return new ParseResult(List.of(), List.of("The file is empty"));
        }
        if (!Currencies.supports(defaultCurrency)) {
            return new ParseResult(List.of(), List.of("Unsupported currency: " + defaultCurrency));
        }

        List<List<String>> records = splitRecords(content);
        if (records.size() > MAX_RECORDS) {
            return new ParseResult(List.of(), List.of("Statement has more than " + MAX_RECORDS + " rows"));
        }

        int headerIndex = findHeader(records);
        if (headerIndex < 0) {
            return new ParseResult(List.of(),
                    List.of("No header row found. Expected a date column and a description column."));
        }

        Map<String, Integer> columns = mapColumns(records.get(headerIndex));
        Integer dateColumn = firstMatch(columns, DATE_HEADERS);
        Integer descriptionColumn = firstMatch(columns, DESC_HEADERS);
        Integer amountColumn = firstMatch(columns, AMOUNT_HEADERS);
        Integer debitColumn = firstMatch(columns, DEBIT_HEADERS);
        Integer creditColumn = firstMatch(columns, CREDIT_HEADERS);
        Integer currencyColumn = firstMatch(columns, CURRENCY_HEADERS);
        Integer referenceColumn = firstMatch(columns, REF_HEADERS);

        if (dateColumn == null || descriptionColumn == null) {
            return new ParseResult(List.of(), List.of("Header must contain a date column and a description column"));
        }
        if (amountColumn == null && debitColumn == null && creditColumn == null) {
            return new ParseResult(List.of(), List.of("Header must contain amount, or debit and credit columns"));
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            int line = i + 1;
            if (isBlank(record)) {
                continue;
            }
            try {
                String rawDate = cell(record, dateColumn);
                String description = cell(record, descriptionColumn);
                String rawCurrency = currencyColumn == null ? defaultCurrency : cell(record, currencyColumn);
                String currency = rawCurrency.isBlank() ? defaultCurrency : rawCurrency.trim().toUpperCase(Locale.ROOT);
                String rawAmount = "";
                Direction fixedDirection = null;
                if (amountColumn != null && !cell(record, amountColumn).isBlank()) {
                    rawAmount = cell(record, amountColumn);
                } else if (debitColumn != null && !cell(record, debitColumn).isBlank()) {
                    rawAmount = cell(record, debitColumn);
                    fixedDirection = Direction.DEBIT;
                } else if (creditColumn != null && !cell(record, creditColumn).isBlank()) {
                    rawAmount = cell(record, creditColumn);
                    fixedDirection = Direction.CREDIT;
                }

                if (description.isBlank() && rawAmount.isBlank()) {
                    continue;
                }
                if (description.isBlank()) {
                    throw new IllegalArgumentException("missing description");
                }
                if (rawAmount.isBlank()) {
                    throw new IllegalArgumentException("missing amount");
                }
                if (!Currencies.supports(currency)) {
                    throw new IllegalArgumentException("unsupported currency " + currency);
                }

                LocalDate postedOn = parseDate(rawDate);
                AmountWithSign parsed = parseAmount(rawAmount, currency);
                Direction direction = fixedDirection != null
                        ? fixedDirection
                        : convention.resolve(parsed.negative());

                String reference = referenceColumn == null ? "" : cell(record, referenceColumn);
                rows.add(new ParsedRow(line, postedOn, description.trim(),
                        Money.ofMinor(parsed.minor(), currency), direction,
                        reference.isBlank() ? null : reference.trim()));
            } catch (RuntimeException problem) {
                errors.add("Row " + line + ": " + problem.getMessage());
            }
        }
        return new ParseResult(rows, errors);
    }

    /** Escapes a value for CSV export so spreadsheets cannot execute it as a formula. */
    public static String sanitizeCell(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.stripLeading();
        boolean dangerous = !trimmed.isEmpty()
                && (trimmed.charAt(0) == '=' || trimmed.charAt(0) == '+' || trimmed.charAt(0) == '-'
                || trimmed.charAt(0) == '@' || trimmed.charAt(0) == '\t' || trimmed.charAt(0) == '\r');
        String safe = dangerous ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private static LocalDate parseDate(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing date");
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("unrecognised date '" + value + "' (expected e.g. "
                + LocalDate.of(2026, 1, 31).format(DISPLAY) + ")");
    }

    private record AmountWithSign(long minor, boolean negative) {
    }

    private static AmountWithSign parseAmount(String raw, String currency) {
        String trimmed = raw.trim();
        boolean parenthesised = trimmed.startsWith("(") && trimmed.endsWith(")");
        Money money = Money.parse(trimmed, currency);
        return new AmountWithSign(Math.abs(money.minor()), parenthesised || money.isNegative());
    }

    private static int findHeader(List<List<String>> records) {
        int limit = Math.min(HEADER_SCAN_LIMIT, records.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Integer> columns = mapColumns(records.get(i));
            if (firstMatch(columns, DATE_HEADERS) != null && firstMatch(columns, DESC_HEADERS) != null) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Integer> mapColumns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = normalizeHeader(header.get(i));
            if (!key.isEmpty()) {
                columns.putIfAbsent(key, i);
            }
        }
        return columns;
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Integer firstMatch(Map<String, Integer> columns, Set<String> candidates) {
        for (String candidate : candidates) {
            Integer index = columns.get(candidate);
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private static String cell(List<String> record, int index) {
        return index < record.size() && record.get(index) != null ? record.get(index).trim() : "";
    }

    private static boolean isBlank(List<String> record) {
        return record.stream().allMatch(value -> value == null || value.isBlank());
    }

    private static List<List<String>> splitRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (quoted) {
                if (ch == '"') {
                    if (next == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> quoted = true;
                case ',' -> {
                    current.add(cell.toString());
                    cell.setLength(0);
                }
                case '\n' -> {
                    current.add(cell.toString());
                    cell.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                }
                case '\r' -> {
                    // handled by \n
                }
                default -> cell.append(ch);
            }
        }
        if (cell.length() > 0 || !current.isEmpty()) {
            current.add(cell.toString());
            records.add(current);
        }
        return records;
    }
}
