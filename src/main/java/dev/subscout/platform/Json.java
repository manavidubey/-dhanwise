package dev.subscout.platform;

import java.util.List;

/**
 * Minimal JSON writer for evidence payloads. Keeping this local avoids binding internal
 * audit strings to a serialisation library version.
 */
public final class Json {

    private Json() {
    }

    public static String obj(Object... keysAndValues) {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(quote(String.valueOf(keysAndValues[i]))).append(':')
                    .append(value(keysAndValues[i + 1]));
        }
        return builder.append('}').toString();
    }

    public static String arr(List<?> items) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(value(items.get(i)));
        }
        return builder.append(']').toString();
    }

    public static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private static String value(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw.toString();
        }
        if (raw instanceof List<?> list) {
            return arr(list);
        }
        return quote(raw.toString());
    }
}
