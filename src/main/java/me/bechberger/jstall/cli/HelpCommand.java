package me.bechberger.jstall.cli;

import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jstall.Main;

import java.util.concurrent.Callable;

/**
 * Displays the same help output as --help.
 * Useful when --help is intercepted by an outer CLI framework (e.g., CF CLI plugin).
 */
@Command(
    name = "help",
    description = "Show help (same as --help)"
)
public class HelpCommand implements Callable<Integer> {

    Spec spec;

    @Override
    public Integer call() {
        if (spec != null) {
            Main main = spec.getParent(Main.class);
            if (main != null) {
                main.run();
                return 0;
            }
        }
        System.out.println("Usage: jstall <command> [options]");
        System.out.println("Run with --help for full usage information.");
        return 0;
    }
}
