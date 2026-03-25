package me.bechberger.jstall.cli.good;

import me.bechberger.jstall.cli.RunCommandUtil;
import me.bechberger.jstall.testframework.TestAppLauncher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for ListCommand and JVMDiscovery
 */
class ListCommandTest {

    static TestAppLauncher launcher;
    static final String mainClass = "me.bechberger.jstall.testapp.DeadlockTestApp";

    @BeforeAll
    static void setup() throws IOException, InterruptedException {
        launcher = new TestAppLauncher();
        launcher.launch(mainClass, "deadlock");
        // Give the JVM time to fully register with the Attach API
        Thread.sleep(500);
    }

    @AfterAll
    static void teardown() {
        launcher.stop();
    }

    @Test
    void testListCommand_NoFilter() {
        RunCommandUtil.run("list").log().hasNoError().output().contains(launcher.getPid() + " " + mainClass);
    }

    @Test
    void testListCommand_FilterShell() {
        RunCommandUtil.runWithShell("list").hasNoError().output().contains(launcher.getPid() + " " + mainClass);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DeadLock", mainClass})
    void testListCommand_Filter(String filter) {
        RunCommandUtil.run("list", filter).hasNoError().output().contains(launcher.getPid() + " " + mainClass);
    }

    @Test
    void testListCommand_NothingFound() {
        RunCommandUtil.run("list", "CertainlyNoSuchString").hasError().output().isEmpty();
    }

    @Test
    void testAllLinesSmallerThan80Chars() {
        RunCommandUtil.run("list").hasNoError().outputLines().allMatch(line -> line.length() < 200, "Line exceeds 80 chars: %s");
    }

}