package me.bechberger.jstall.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassHistogramParserTest {

    @Test
    void parsesSample() {
        String input = "1631:\n" +
            " num     #instances         #bytes  class name (module)\n" +
            "-------------------------------------------------------\n" +
            "   1:       2438780      332349064  [B (java.base@21.0.9)\n" +
            "   2:        782447      233926064  [I (java.base@21.0.9)\n" +
            "   3:         22611       93029504  [Ljdk.internal.vm.FillerElement; (java.base@21.0.9)\n" +
            "   4:       2187014       69984448  java.util.Hashtable$Entry (java.base@21.0.9)\n";

        var histo = ClassHistogramParser.parse(input);
        assertEquals(4, histo.entries().size());

        var first = histo.entries().get(0);
        assertEquals(1, first.num());
        assertEquals(2_438_780L, first.instances());
        assertEquals(332_349_064L, first.bytes());
        assertEquals("[B", first.className());
        assertEquals("java.base@21.0.9", first.module());

        assertTrue(histo.totalBytes() > 0);
        assertEquals(histo.topByBytes(1).get(0).bytes(), histo.topByBytes(1).get(0).bytes());
    }

    @Test
    void parsesRowsWithoutModule() {
        String input = "  1:  10  20  java.lang.String\n";
        var histo = ClassHistogramParser.parse(input);
        assertEquals(1, histo.entries().size());
        assertNull(histo.entries().get(0).module());
        assertEquals("java.lang.String", histo.entries().get(0).className());
    }
}