package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import org.junit.jupiter.api.Test;

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

        long now = System.currentTimeMillis();
        CollectedData cd1 = new CollectedData(now, h1, Map.of());
        CollectedData cd2 = new CollectedData(now + 5000, h2, Map.of());
        
        ResolvedData data = new ResolvedData(
            List.of(),  // No thread dumps needed for histogram analyzer
            Map.of(),   // No system properties
            null,       // No environment
            Map.of("gc-class-histogram", List.of(cd1, cd2))
        );

        var a = new ClassHistogramDiffAnalyzer();
        AnalyzerResult r = a.analyze(data, Map.of("top", 5, "sort", "bytes"));

        assertTrue(r.output().contains("Class histogram delta"));
        assertTrue(r.output().contains("a.A"));
    }
}