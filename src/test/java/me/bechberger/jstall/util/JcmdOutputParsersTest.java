package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JcmdOutputParsersTest {

    @Test
    public void parseVmSystemProperties_ignoresHeaderAndTimestampAndParsesKeyValues() {
        String input = "17432:\n" +
                       "#Mon Feb 09 12:38:52 CET 2026\n" +
                       "java.vendor=JetBrains s.r.o.\n" +
                       "java.vendor.url=https\\://openjdk.org/\n" +
                       "empty.value=\n" +
                       "noequals\n" +
                       "   java.version = 21.0.9   \n";

        Map<String, String> props = JcmdOutputParsers.parseVmSystemProperties(input);

        assertEquals("JetBrains s.r.o.", props.get("java.vendor"));
        assertEquals("https\\://openjdk.org/", props.get("java.vendor.url"));
        assertEquals("", props.get("empty.value"));
        assertEquals("21.0.9", props.get("java.version"));

        assertFalse(props.containsKey("17432"));
    }

    @Test
    public void parseVmSystemProperties_emptyInput() {
        assertTrue(JcmdOutputParsers.parseVmSystemProperties("").isEmpty());
        assertTrue(JcmdOutputParsers.parseVmSystemProperties("\n\n").isEmpty());
        assertTrue(JcmdOutputParsers.parseVmSystemProperties(null).isEmpty());
    }
}