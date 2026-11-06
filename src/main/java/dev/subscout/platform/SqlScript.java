package dev.subscout.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into statements, respecting single-quoted literals and comments.
 * Deliberately small: the migration set is our own, and a naive split on ';' breaks on
 * literals such as default 'purchase'.
 */
public final class SqlScript {

    private SqlScript() {
    }

    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (ch == '\n') {
                    inLineComment = false;
                    current.append(ch);
                }
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inQuote) {
                current.append(ch);
                if (ch == '\'') {
                    if (next == '\'') {
                        current.append(next);
                        i++;
                    } else {
                        inQuote = false;
                    }
                }
                continue;
            }
            if (ch == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (ch == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (ch == '\'') {
                inQuote = true;
                current.append(ch);
                continue;
            }
            if (ch == ';') {
                addIfPresent(statements, current);
                continue;
            }
            current.append(ch);
        }
        addIfPresent(statements, current);
        return statements;
    }

    private static void addIfPresent(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
