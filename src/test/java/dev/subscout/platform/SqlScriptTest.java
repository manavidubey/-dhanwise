package dev.subscout.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlScriptTest {

    @Test
    void doesNotSplitOnSemicolonsInsideLiterals() {
        List<String> statements = SqlScript.split(
                "create table a (x text default 'a;b'); insert into a(x) values ('c;d');");
        assertEquals(2, statements.size());
        assertEquals("create table a (x text default 'a;b')", statements.get(0));
    }

    @Test
    void ignoresLineAndBlockComments() {
        List<String> statements = SqlScript.split("""
                -- create table nope (x text);
                /* also; not a statement */
                select 1;
                """);
        assertEquals(1, statements.size());
        assertEquals("select 1", statements.get(0));
    }

    @Test
    void handlesEscapedQuote() {
        List<String> statements = SqlScript.split("insert into a values ('it''s; fine');");
        assertEquals(1, statements.size());
    }
}
