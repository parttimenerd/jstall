package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.JvmSupportAnalyzer;

/**
 * Checks whether the target JVM is reasonably up-to-date based on java.version.date.
 */
@Command(
    name = "jvm-support",
    description = "Check whether the target JVM is likely still supported (based on java.version.date)"
)
public class JvmSupportCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new JvmSupportAnalyzer();
    }
}