package me.bechberger.jstall.util;

import me.bechberger.femtocli.RunResult;

public record CommandResult(String out, String err, int exitCode, long pid) {
    public RunResult toRunResult() {
        return new RunResult(out, err, exitCode);
    }
}