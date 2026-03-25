package me.bechberger.jstall.cli;

import me.bechberger.femtocli.RunResult;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.ListAssert;
import org.assertj.core.api.StringAssert;

import java.util.Objects;

public class RunResultAssert extends AbstractAssert<RunResultAssert, RunResult> {
    public RunResultAssert(RunResult actual) {
        super(actual, RunResultAssert.class);
        Objects.requireNonNull(actual, "RunResult must not be null");
    }

    public RunResultAssert hasExitCode(int expectedExitCode) {
        if (actual.exitCode() != expectedExitCode) {
            failWithMessage("Expected exit code <%d> but was <%d>", expectedExitCode, actual.exitCode());
        }
        return this;
    }

    public RunResultAssert hasOutputContaining(String expectedSubstring) {
        if (!actual.out().contains(expectedSubstring)) {
            failWithMessage("Expected output to contain <%s> but was <%s>", expectedSubstring, actual.out());
        }
        return this;
    }

    public RunResultAssert hasErrorContaining(String expectedSubstring) {
        if (!actual.err().contains(expectedSubstring)) {
            failWithMessage("Expected error output to contain <%s> but was <%s>", expectedSubstring, actual.err());
        }
        return this;
    }

    public RunResultAssert hasNoOutput() {
        if (!actual.out().isEmpty()) {
            failWithMessage("Expected no output but was <%s>", actual.out());
        }
        return this;
    }

    public RunResultAssert hasNoErrorOutput() {
        if (!actual.err().isEmpty()) {
            failWithMessage("Expected no error output but was <%s>", actual.err());
        }
        return this;
    }

    /**
     * No error output and exit code 0
     */
    public RunResultAssert hasNoError() {
        return hasExitCode(0).hasNoErrorOutput();
    }

    public StringAssert output() {
        return new StringAssert(actual.out()).as("output");
    }

    public ListAssert<String> outputLines() {
        return new ListAssert<>(actual.out().lines().toList()).as("output lines");
    }

    public StringAssert errorOutput() {
        return new StringAssert(actual.err()).as("error output");
    }

    public RunResult get() {
        return actual;
    }

    public RunResultAssert hasError() {
        if (actual.exitCode() == 0 && actual.err().isEmpty()) {
            failWithMessage("Expected an error but exit code was 0 and no error output");
        }
        return this;
    }

    public RunResultAssert log() {
        System.out.println("Exit code: " + actual.exitCode());
        System.out.println("Output:\n" + actual.out());
        System.out.println("Error output:\n" + actual.err());
        return this;
    }
}