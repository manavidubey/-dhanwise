package dev.subscout.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CsvStatementParserTest {

    private final CsvStatementParser parser = new CsvStatementParser();

    private static final String STATEMENT = """
            HDFC Bank Credit Card Statement
            Statement period: 01/02/2026 - 28/02/2026

            Date,Narration,Debit,Credit,Reference
            01/02/2026,"NETFLIX, COM",649.00,,REF1
            05/02/2026,SPOTIFY,119.00,,REF2
            10/02/2026,SALARY,,50000.00,REF3
            not-a-date,BROKEN,100.00,,REF4
            """;

    @Test
    void skipsPreambleAndParsesRows() {
        CsvStatementParser.ParseResult result = parser.parse(STATEMENT, "INR");

        assertEquals(3, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).startsWith("Row 8:"), result.errors().get(0));
    }

    @Test
    void readsQuotedDescriptionWithComma() {
        CsvStatementParser.ParseResult result = parser.parse(STATEMENT, "INR");
        assertEquals("NETFLIX, COM", result.rows().get(0).description());
    }

    @Test
    void separatesDirectionFromAmount() {
        CsvStatementParser.ParseResult result = parser.parse(STATEMENT, "INR");
        assertEquals(64900, result.rows().get(0).amount().minor());
        assertEquals(CsvStatementParser.Direction.DEBIT, result.rows().get(0).direction());
        assertEquals(5000000, result.rows().get(2).amount().minor());
        assertEquals(CsvStatementParser.Direction.CREDIT, result.rows().get(2).direction());
    }

    @Test
    void parsesAmountColumnWithCurrencySymbolUsingCardConvention() {
        String content = """
                date,description,amount,currency
                2026-02-01,OPENAI,US$ 20.00,USD
                """;
        CsvStatementParser.ParseResult result = parser.parse(
                content, "INR", CsvStatementParser.SignConvention.POSITIVE_IS_DEBIT);
        assertEquals(1, result.rows().size());
        assertEquals(2000, result.rows().get(0).amount().minor());
        assertEquals(CsvStatementParser.Direction.DEBIT, result.rows().get(0).direction());
        assertEquals("USD", result.rows().get(0).amount().currency());
    }

    @Test
    void readsNegativeAmountAsDebitForBankStatements() {
        String content = """
                date,description,amount
                2026-02-01,NETFLIX,-649.00
                """;
        CsvStatementParser.ParseResult result = parser.parse(content, "INR");
        assertEquals(CsvStatementParser.Direction.DEBIT, result.rows().get(0).direction());
        assertEquals(64900, result.rows().get(0).amount().minor());
    }

    @Test
    void reportsMissingHeader() {
        CsvStatementParser.ParseResult result = parser.parse("a,b,c\n1,2,3", "INR");
        assertFalse(result.errors().isEmpty());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void sanitisesFormulaInjectionForExport() {
        String dangerous = "=cmd|' /C calc'!A0";
        String safe = CsvStatementParser.sanitizeCell(dangerous);
        assertTrue(safe.startsWith("'="), safe);
        assertNotNull(safe);
    }

    @Test
    void quotesCellsContainingCommas() {
        assertEquals("\"a,b\"", CsvStatementParser.sanitizeCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvStatementParser.sanitizeCell("say \"hi\""));
    }
}
