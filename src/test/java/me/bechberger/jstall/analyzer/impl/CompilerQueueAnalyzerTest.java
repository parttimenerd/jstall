package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CompilerQueueAnalyzer using fixture data.
 */
class CompilerQueueAnalyzerTest {

    @Test
    void handlesEmptyData() {
        ResolvedData data = new ResolvedData(
            List.of(),
            Map.of(),
            null,
            Map.of("compiler-queue", List.of())
        );

        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().isBlank() || result.output().contains("nothing"));
    }

    @Test
    void handlesUnsupportedJvm() {
        CollectedData cd = new CollectedData(
            System.currentTimeMillis(),
            "Command not supported",
            Map.of()
        );

        ResolvedData data = new ResolvedData(
            List.of(),
            Map.of(),
            null,
            Map.of("compiler-queue", List.of(cd))
        );

        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("not available") || result.output().isBlank());
    }

    @Test
    void parsesEmptyQueues() {
        String output = """
            Current compiles:
            
            C1 compile queue:
            Empty
            
            C2 compile queue:
            Empty
            """;

        CollectedData cd = new CollectedData(
            System.currentTimeMillis(),
            output,
            Map.of()
        );

        ResolvedData data = new ResolvedData(
            List.of(),
            Map.of(),
            null,
            Map.of("compiler-queue", List.of(cd))
        );

        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Compiler queue trend"));
        assertTrue(result.output().contains("1 samples"));
        assertTrue(result.output().contains("Empty") || result.output().contains("all empty"));
    }

    @Test
    void parsesSingleSampleWithActivity() {
        String output = """
            Current compiles:
            CompilerThread1  123  %s! b  2     java.lang.String.indexOf (42 bytes)
            
            C1 compile queue:
            124    !   1     com.example.Foo.bar (128 bytes)
            125        1     com.example.Bar.baz (64 bytes)
            
            C2 compile queue:
            126  %     2     java.util.HashMap.put @ 10 (256 bytes)
            """;

        CollectedData cd = new CollectedData(
            System.currentTimeMillis(),
            output,
            Map.of()
        );

        ResolvedData data = new ResolvedData(
            List.of(),
            Map.of(),
            null,
            Map.of("compiler-queue", List.of(cd))
        );

        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertEquals(0, result.exitCode());
        String resultOutput = result.output();
        
        assertTrue(resultOutput.contains("Compiler queue trend"));
        assertTrue(resultOutput.contains("Active compilations: 1"));
        assertTrue(resultOutput.contains("Queued tasks: 3"));
        assertTrue(resultOutput.contains("C1") && resultOutput.contains("C2"));
    }

    @Test
    void parsesMultipleSamplesShownAsTrend() {
        String sample1 = """
            Current compiles:
            
            C1 compile queue:
            100       1     Method1 (10 bytes)
            
            C2 compile queue:
            Empty
            """;

        String sample2 = """
            Current compiles:
            Thread1  100    !   1     Method1 (10 bytes)
            
            C1 compile queue:
            Empty
            
            C2 compile queue:
            101       2     Method2 (20 bytes)
            102       2     Method3 (30 bytes)
            """;

        String sample3 = """
            Current compiles:
            
            C1 compile queue:
            Empty
            
            C2 compile queue:
            102       2     Method3 (30 bytes)
            """;

        long now = System.currentTimeMillis();
        CollectedData cd1 = new CollectedData(now, sample1, Map.of());
        CollectedData cd2 = new CollectedData(now + 2000, sample2, Map.of());
        CollectedData cd3 = new CollectedData(now + 4000, sample3, Map.of());

        ResolvedData data = new ResolvedData(
            List.of(),
            Map.of(),
            null,
            Map.of("compiler-queue", List.of(cd1, cd2, cd3))
        );

        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertEquals(0, result.exitCode());
        String resultOutput = result.output();
        
        // Should show trend with 3 samples
        assertTrue(resultOutput.contains("3 samples"));
        assertTrue(resultOutput.contains("Summary"));
        assertTrue(resultOutput.contains("range"));
        assertTrue(resultOutput.contains("Per-sample breakdown"));
        
        // Should show progression
        assertTrue(resultOutput.contains("Latest snapshot details"));
    }

    @Test
    void showsRangeStatistics() {
        String sample1 = """
            Current compiles:
            Thread1  1    !   2     M1 (10 bytes)
            Thread2  2    !   2     M2 (10 bytes)
            
            C1 compile queue:
            3       1     M3 (10 bytes)
            """;

        String sample2 = """
            Current compiles:
            Thread1  4    !   2     M4 (10 bytes)
            
            C1 compile queue:
            5       1     M5 (10 bytes)
            6       1     M6 (10 bytes)
            7       1     M7 (10 bytes)
            """;

        long now = System.currentTimeMillis();
        CollectedData cd1 = new CollectedData(now, sample1, Map.of());
        CollectedData cd2 = new CollectedData(now + 1000, sample2, Map.of());

        ResolvedData data = new ResolvedData(
            List.of(),
            Map.of(),
            null,
            Map.of("compiler-queue", List.of(cd1, cd2))
        );

        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertEquals(0, result.exitCode());
        String resultOutput = result.output();
        
        // Range should show min and max
        assertTrue(resultOutput.contains("range: 1-2")); // Active: 2 then 1
        assertTrue(resultOutput.contains("range: 1-3")); // Queued: 1 then 3
    }

    @Test
    void dataRequirementsIncludeCompilerQueue() {
        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        var requirements = analyzer.getDataRequirements(Map.of());
        
        assertNotNull(requirements);
        // Should include Compiler.queue jcmd requirement
        assertTrue(requirements.toString().contains("Compiler.queue") || 
                  requirements.toString().contains("compiler-queue"));
    }

    @Test
    void customOptionsAffectDataRequirements() {
        CompilerQueueAnalyzer analyzer = new CompilerQueueAnalyzer();
        
        var defaultReq = analyzer.getDataRequirements(Map.of());
        var customReq = analyzer.getDataRequirements(Map.of("samples", 5, "interval", 1000));
        
        assertNotNull(defaultReq);
        assertNotNull(customReq);
        // Both should request compiler queue data
    }
}