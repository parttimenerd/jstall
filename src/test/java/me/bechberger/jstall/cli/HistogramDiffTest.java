package me.bechberger.jstall.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static me.bechberger.jstall.cli.Util.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramDiffTest {

    @TempDir
    Path tmp;

    @Test
    void diffsTwoHistogramsAndFindsTopGrower() throws Exception {
        String before = """
            num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:            10            100  a.A (java.base@21)
               2:             1             10  b.B (java.base@21)
            """;

        String after = """
            num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:            30            500  a.A (java.base@21)
               2:             1             10  b.B (java.base@21)
               3:             2             40  c.C (java.base@21)
            """;

        Path beforeFile = tmp.resolve("before.txt");
        Path afterFile = tmp.resolve("after.txt");
        Files.writeString(beforeFile, before);
        Files.writeString(afterFile, after);

        var r = run("histogram", beforeFile.toString(), afterFile.toString(), "--top", "1", "--sort", "bytes");
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("Top 1 deltas"));
        // A grows by +400 bytes
        assertTrue(r.out().contains("a.A"));
        assertTrue(r.out().contains("400"));
    }
}