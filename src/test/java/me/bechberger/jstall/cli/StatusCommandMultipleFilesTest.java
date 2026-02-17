package me.bechberger.jstall.cli;

import me.bechberger.femtocli.RunResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StatusCommandMultipleFilesTest {

    @Test
    void statusAcceptsMultipleSyntheticDumpFilesAsMultipleDumps() throws Exception {
        Path dump1 = Files.createTempFile("jstall-status-dump-1", ".txt");
        Path dump2 = Files.createTempFile("jstall-status-dump-2", ".txt");

        try {
            // Minimal valid thread dump format that the parser accepts.
            // Two different timestamps ensure the loader treats them as separate dumps.
            String dumpContent1 = """
                2024-12-29 13:00:00
                Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):

                \"main\" #1 prio=5 os_prio=0 tid=0x00007f8b0c00b800 nid=0x1 runnable [0x00007f8b14e5d000]
                   java.lang.Thread.State: RUNNABLE
                    at java.lang.Object.wait(java.base@21/Native Method)
                """;

            String dumpContent2 = """
                2024-12-29 13:00:01
                Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):

                \"main\" #1 prio=5 os_prio=0 tid=0x00007f8b0c00b800 nid=0x1 runnable [0x00007f8b14e5d000]
                   java.lang.Thread.State: RUNNABLE
                    at java.lang.Object.wait(java.base@21/Native Method)
                """;

            Files.writeString(dump1, dumpContent1);
            Files.writeString(dump2, dumpContent2);

            RunResult result = Util.run("status", dump1.toString(), dump2.toString());

            // Regression assertion: before the fix, each file was analyzed separately and the nested
            // most-work analyzer complained about only having one dump.
            assertFalse(result.err().contains("requires at least 2 dumps"), () -> "stderr was: " + result.err());

        } finally {
            Files.deleteIfExists(dump1);
            Files.deleteIfExists(dump2);
        }
    }
}