package me.bechberger.jstall.cli;

import org.junit.jupiter.api.Test;

import static me.bechberger.jstall.cli.Util.run;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramCommandTest {

    @Test
    void passingPidDoesNotShowFilePathErrorAnymore() {
        var r = run("histogram", "999999");
        assertTrue(r.exitCode() != 0);
        assertTrue(!r.err().contains("expects histogram file path"));
    }
}