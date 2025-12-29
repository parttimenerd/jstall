package me.bechberger.jstall.analyzer;

/**
 * Result of running an analyzer.
 *
 * @param output The output to display
 * @param exitCode The exit code (0 = OK, 2 = deadlock, etc.)
 * @param shouldDisplay Whether this result should be displayed (false means analyzer has nothing to report)
 */
public record AnalyzerResult(String output, int exitCode, boolean shouldDisplay) {

    /**
     * Creates a successful result with no output.
     */
    public static AnalyzerResult ok() {
        return new AnalyzerResult("", 0, true);
    }

    /**
     * Creates a successful result with output.
     */
    public static AnalyzerResult ok(String output) {
        return new AnalyzerResult(output, 0, true);
    }

    /**
     * Creates a result indicating nothing to report (will be hidden).
     */
    public static AnalyzerResult nothing() {
        return new AnalyzerResult("", 0, false);
    }

    /**
     * Creates a result indicating a deadlock was detected.
     */
    public static AnalyzerResult deadlock(String output) {
        return new AnalyzerResult(output, 2, true);
    }

    /**
     * Creates a result with a custom exit code.
     */
    public static AnalyzerResult withExitCode(String output, int exitCode) {
        return new AnalyzerResult(output, exitCode, true);
    }
}