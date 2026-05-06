package me.bechberger.jstall.analyzer;

/**
 * Result of running an analyzer.
 *
 * @param output The output to display (derived from structured)
 * @param exitCode The exit code (0 = OK, 2 = deadlock, etc.)
 * @param shouldDisplay Whether this result should be displayed (false means analyzer has nothing to report)
 * @param structured Structured output (always non-null for displayable results)
 */
public record AnalyzerResult(String output, int exitCode, boolean shouldDisplay, AnalyzerOutput structured) {

    /** Creates a successful result with no output. */
    public static AnalyzerResult ok() {
        return new AnalyzerResult("", 0, true, new AnalyzerOutput.TextOutput(""));
    }

    /** Creates a successful result wrapping a plain string in TextOutput. */
    public static AnalyzerResult ok(String output) {
        String safe = output == null ? "" : output;
        return new AnalyzerResult(safe, 0, true, new AnalyzerOutput.TextOutput(safe));
    }

    /** Creates a successful result with structured output. */
    public static AnalyzerResult ok(AnalyzerOutput structured) {
        return new AnalyzerResult(structured.render(), 0, true, structured);
    }

    /** Creates a result indicating nothing to report (will be hidden). */
    public static AnalyzerResult nothing() {
        return new AnalyzerResult("", 0, false, null);
    }

    /** Creates a result indicating a deadlock was detected. */
    public static AnalyzerResult deadlock(String output) {
        String safe = output == null ? "" : output;
        return new AnalyzerResult(safe, 2, true, new AnalyzerOutput.TextOutput(safe));
    }

    /** Creates a deadlock result with structured output. */
    public static AnalyzerResult deadlock(AnalyzerOutput structured) {
        return new AnalyzerResult(structured.render(), 2, true, structured);
    }

    /** Creates a result with a custom exit code. */
    public static AnalyzerResult withExitCode(String output, int exitCode) {
        String safe = output == null ? "" : output;
        return new AnalyzerResult(safe, exitCode, true, new AnalyzerOutput.TextOutput(safe));
    }

    /** Creates a result with structured output and a custom exit code. */
    public static AnalyzerResult withExitCode(AnalyzerOutput structured, int exitCode) {
        return new AnalyzerResult(structured.render(), exitCode, true, structured);
    }
}