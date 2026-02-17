package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.RunResult;

public class Util {
    private Util() {
        // utility class
    }

    static RunResult run(Object command, String... args) {
        return FemtoCli.builder().commandConfig(Main::setFemtoCliCommandConfig).runCaptured(command, args);
    }

    static RunResult run(String... args) {
        return FemtoCli.builder().commandConfig(Main::setFemtoCliCommandConfig).runCaptured(new Main(), args);
    }
}