package com.redactado.noray.log;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class LogFormatTest {

    @Test
    void secretsAreRedactedButIdsAreKept() {
        String id = "ab".repeat(32);
        String in = "boot noray.net.mesh.psk=" + "f".repeat(64) + " peer " + id + " passphrase: hunter2, token=abc";
        String out = Redactor.redact(in);
        assertFalse(out.contains("hunter2"));
        assertFalse(out.contains("f".repeat(64)));
        assertFalse(out.contains("abc"));
        assertTrue(out.contains("noray.net.mesh.psk=<redacted>"));
        assertTrue(out.contains(id), "node ids are public");
        assertEquals("nothing to hide", Redactor.redact("nothing to hide"));
    }

    @Test
    void jsonLinesAreValidAndEscaped() {
        LogRecord r = new LogRecord(Level.WARNING, "line \"one\"\nsecret=x");
        r.setLoggerName("noray.test");
        r.setThrown(new IllegalStateException("boom"));
        String line = new JsonLogFormatter().format(r);
        assertTrue(line.endsWith("}\n"));
        assertEquals(1, line.chars().filter(c -> c == '\n').count(), "one record, one line");
        assertTrue(line.contains("\"level\":\"WARNING\""));
        assertTrue(line.contains("\"logger\":\"noray.test\""));
        assertTrue(line.contains("line \\\"one\\\"\\nsecret=<redacted>"));
        assertTrue(line.contains("\"error\":\"java.lang.IllegalStateException: boom"));
    }

    @Test
    void levelNamesMapToJul() {
        assertEquals(Level.FINE, LogSetup.level("debug"));
        assertEquals(Level.WARNING, LogSetup.level("WARN"));
        assertEquals(Level.INFO, LogSetup.level("nonsense"));
    }
}
