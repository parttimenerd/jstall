package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassHistogramDiffAnalyzerTest {

    @Test
    void reportsDeltaWhenHistogramsPresent() {
        String h1 = """
            num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:            10            100  a.A (java.base@21)
            """;
        String h2 = """
            num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:            30            500  a.A (java.base@21)
            """;

        ThreadDump td = new ThreadDump(
            Instant.now(),
            "Test Dump",
            List.<ThreadInfo>of(),
            null,
            null,
            null
        );
        var s1 = new ThreadDumpSnapshot(td, "raw1", null, h1);
        var s2 = new ThreadDumpSnapshot(td, "raw2", null, h2);

        var a = new ClassHistogramDiffAnalyzer();
        AnalyzerResult r = a.analyze(List.of(s1, s2), Map.of("top", 5, "sort", "bytes"));

        assertTrue(r.output().contains("Class histogram delta"));
        assertTrue(r.output().contains("a.A"));
    }
}