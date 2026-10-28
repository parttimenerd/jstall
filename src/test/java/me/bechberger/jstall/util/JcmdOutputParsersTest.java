package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    public void parseTable_basicTableWithHeaders() {
        String input = "17432:\n" +
                       "#Mon Feb 09 12:38:52 CET 2026\n" +
                       " num     #instances         #bytes  class name\n" +
                       "-------------------------------------------------------\n" +
                       "   1:       1234              567890  [B (java.base@21.0.9)\n" +
                       "   2:       5678              901234  java.lang.Object\n" +
                       "   3:       100               50000   com.example.Test\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input);

        assertEquals(3, table.size());
        assertFalse(table.isEmpty());
        
        List<String> headers = table.headers();
        assertEquals(4, headers.size());
        assertEquals("num", headers.get(0));
        assertEquals("#instances", headers.get(1));
        assertEquals("#bytes", headers.get(2));
        assertEquals("class name", headers.get(3));

        JcmdOutputParsers.TableRow row0 = table.get(0);
        assertEquals("1", row0.get(0));
        assertEquals("1234", row0.get(1));
        assertEquals("567890", row0.get(2));
        assertEquals("[B (java.base@21.0.9)", row0.get(3));
        
        // Test column name access
        assertEquals("1", row0.get("num"));
        assertEquals("1234", row0.get("#instances"));
        assertEquals("567890", row0.get("#bytes"));
        assertEquals("[B (java.base@21.0.9)", row0.get("class name"));

        // Test numeric parsing
        assertEquals(1L, row0.getLong("num"));
        assertEquals(1234L, row0.getLong("#instances"));
        assertEquals(567890L, row0.getLong("#bytes"));
        assertEquals(1234, row0.getInt("#instances"));
    }

    @Test
    public void parseTable_numericValuesWithSeparators() {
        String input = " num     count          size\n" +
                       "   1:    1,234,567      100_000_000\n" +
                       "   2:    9,876,543      2,500,000\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input);

        assertEquals(2, table.size());
        
        JcmdOutputParsers.TableRow row0 = table.get(0);
        assertEquals(1L, row0.getLong("num"));
        assertEquals(1234567L, row0.getLong("count"));
        assertEquals(100000000L, row0.getLong("size"));
        
        JcmdOutputParsers.TableRow row1 = table.get(1);
        assertEquals(2L, row1.getLong("num"));
        assertEquals(9876543L, row1.getLong("count"));
        assertEquals(2500000L, row1.getLong("size"));
    }

    @Test
    public void parseTable_withoutRowNumbers() {
        String input = "Name     Value\n" +
                       "foo      123\n" +
                       "bar      456\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input,
                JcmdOutputParsers.TableParserConfig.builder()
                        .hasRowNumbers(false)
                        .build());

        assertEquals(2, table.size());
        assertEquals("foo", table.get(0).get(0));
        assertEquals("123", table.get(0).get(1));
        assertEquals("bar", table.get(1).get(0));
    }

    @Test
    public void parseTable_withoutHeaders() {
        String input = "   1:    foo      123\n" +
                       "   2:    bar      456\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input,
                JcmdOutputParsers.TableParserConfig.builder()
                        .hasHeaders(false)
                        .build());

        assertEquals(2, table.size());
        assertTrue(table.headers().isEmpty());
        assertEquals("1", table.get(0).get(0));
        assertEquals("foo", table.get(0).get(1));
        assertEquals("123", table.get(0).get(2));
    }

    @Test
    public void parseTable_customDelimiter() {
        String input = "Name|Value|Description\n" +
                       "foo|100|A test\n" +
                       "bar|200|Another test\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input,
                JcmdOutputParsers.TableParserConfig.builder()
                        .delimiter("|")
                        .hasRowNumbers(false)
                        .build());

        assertEquals(2, table.size());
        assertEquals("foo", table.get(0).get(0));
        assertEquals("100", table.get(0).get(1));
        assertEquals("A test", table.get(0).get(2));
    }

    @Test
    public void parseTable_emptyInput() {
        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable("");
        assertTrue(table.isEmpty());
        assertEquals(0, table.size());

        table = JcmdOutputParsers.parseTable(null);
        assertTrue(table.isEmpty());
    }

    @Test
    public void parseTable_accessNonExistentColumn() {
        String input = "Name     Value\n" +
                       "   1:    foo      123\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input);

        JcmdOutputParsers.TableRow row = table.get(0);
        assertNull(row.get(10)); // Invalid index
        assertNull(row.get("NonExistent")); // Invalid column name
        assertNull(row.getLong("NonExistent"));
        
        // Verify valid access
        assertEquals("1", row.get("Name"));
        assertEquals("foo", row.get("Value"));
    }

    @Test
    public void parseTable_nonNumericValue() {
        String input = "Name     Value     Text\n" +
                       "   1:    foo       abc\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input);

        JcmdOutputParsers.TableRow row = table.get(0);
        assertNull(row.getLong("Value")); // "foo" is not a number
        assertNull(row.getInt("Value"));
        assertNull(row.getLong("Text")); // "abc" is not a number
    }

    @Test
    public void parseTable_realHistogramFormat() {
        // Real format from GC.class_histogram command
        String input = "1631:\n" +
                       " num     #instances         #bytes  class name (module)\n" +
                       "-------------------------------------------------------\n" +
                       "   1:       2438780      332349064  [B (java.base@21.0.9)\n" +
                       "   2:        782447      233926064  [I (java.base@21.0.9)\n" +
                       "   3:         22611       93029504  [Ljdk.internal.vm.FillerElement; (java.base@21.0.9)\n";

        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(input);

        assertEquals(3, table.size());
        
        List<String> headers = table.headers();
        assertEquals(4, headers.size());
        assertEquals("num", headers.get(0));
        assertEquals("#instances", headers.get(1));
        assertEquals("#bytes", headers.get(2));
        assertEquals("class name (module)", headers.get(3));

        // Verify first row
        JcmdOutputParsers.TableRow row0 = table.get(0);
        assertEquals("1", row0.get("num"));
        assertEquals("2438780", row0.get("#instances"));
        assertEquals("332349064", row0.get("#bytes"));
        assertEquals("[B (java.base@21.0.9)", row0.get("class name (module)"));
        
        // Verify numeric parsing
        assertEquals(1, row0.getInt("num"));
        assertEquals(2438780, row0.getInt("#instances"));
        assertEquals(332349064L, row0.getLong("#bytes"));
        
        // Verify second row
        JcmdOutputParsers.TableRow row1 = table.get(1);
        assertEquals(2, row1.getInt("num"));
        assertEquals(782447, row1.getInt("#instances"));
        assertEquals(233926064L, row1.getLong("#bytes"));
        assertEquals("[I (java.base@21.0.9)", row1.get("class name (module)"));
    }
}