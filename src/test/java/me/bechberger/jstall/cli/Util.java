package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.minicli.MiniCli;
import me.bechberger.minicli.RunResult;

public class Util {
    private Util() {
        // utility class
    }

    static RunResult run(Object command, String... args) {
        return MiniCli.builder().commandConfig(Main::setMiniCliCommandConfig).runCaptured(command, args);
    }

    static RunResult run(String... args) {
        return MiniCli.builder().commandConfig(Main::setMiniCliCommandConfig).runCaptured(new Main(), args);
    }
}