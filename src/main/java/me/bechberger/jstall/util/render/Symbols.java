package me.bechberger.jstall.util.render;

/**
 * Terminal symbol table with Unicode/ASCII fallback support.
 *
 * <p>Provides named accessors for every non-ASCII character used in jstall output,
 * plus a {@link #fmt(String, Object...)} templating method that resolves symbol
 * placeholders before delegating to {@link String#format}.
 *
 * <p>Placeholders in format strings use curly-brace syntax with the Unicode character
 * as the key, making templates self-documenting:
 * <pre>
 *   sym.fmt("heap {↑} %s over window", Cell.formatBytes(delta))
 *   // Unicode → "heap ↑ 123m over window"
 *   // ASCII   → "heap ^ 123m over window"
 * </pre>
 *
 * <p>In ASCII mode ({@code unicode=false}), {@link #fmt} and {@link #sanitize} guarantee
 * that no non-ASCII characters appear in the output. Known symbols are replaced with their
 * ASCII equivalents via the substitution table; any remaining non-ASCII character (e.g.
 * arriving through a {@code %s} argument) is replaced with {@code ?}.
 *
 * <p><b>Debug mode</b>: set the system property {@code jstall.symbols.debug=true} (or call
 * {@link #setDebug(boolean)}) to make {@link #sanitize} print a warning to {@code System.err}
 * whenever it replaces a character with {@code ?}. This reveals gaps in the substitution table
 * during testing. Debug mode is off by default.
 *
 * <p>The two standard instances are {@link #UNICODE} and {@link #ASCII}.
 * Use {@link #of(boolean)} to select between them.
 */
public final class Symbols {

    /** Full Unicode symbols — for Unicode-capable terminals. */
    public static final Symbols UNICODE = new Symbols(true);

    /** ASCII-only symbols — for Windows CP1252 terminals and pipes. */
    public static final Symbols ASCII = new Symbols(false);

    /**
     * Substitution table: maps every Unicode character jstall may emit to its
     * ASCII fallback. Used both for placeholder resolution and final sanitization.
     * Em dash is listed before en dash so the longer sequence matches first.
     */
    private static final String[][] SUBSTITUTIONS = {
        // Arrows
        {"↑",  "^"},
        {"↓",  "v"},
        {"→",  "->"},
        {"←",  "<-"},
        // Dashes — em dash first (longer match avoids leaving a stray char)
        {"—",  " - "},
        {"–",  "-"},
        // Box drawing
        {"─",  "-"},
        {"│",  "|"},
        {"┌",  "+"},
        {"└",  "+"},
        {"┐",  "+"},
        {"┘",  "+"},
        {"├",  "+"},
        {"┤",  "+"},
        {"┬",  "+"},
        {"┴",  "+"},
        {"┼",  "+"},
        // List markers
        {"•",  "*"},
        {"◦",  "o"},
        // Math
        {"Δ",  "d"},
        {"≥",  ">="},
        {"≤",  "<="},
        // Checkmarks / crosses (used in LLM/AI output that may be printed)
        {"✅", "[ok]"},
        {"❌", "[err]"},
        {"⚠️", "[warn]"},
        // Misc
        {"…",  "..."},
    };

    private static volatile boolean debugMode =
        Boolean.getBoolean("jstall.symbols.debug");

    private final boolean unicode;

    private Symbols(boolean unicode) {
        this.unicode = unicode;
    }

    /** Select the appropriate instance based on a runtime unicode-capability flag. */
    public static Symbols of(boolean unicode) {
        return unicode ? UNICODE : ASCII;
    }

    /**
     * Enable or disable debug mode globally. When enabled, {@link #sanitize} prints
     * a warning to {@code System.err} for every character replaced by {@code ?}.
     * Intended for use in tests:
     * <pre>
     *   Symbols.setDebug(true);
     *   // ... exercise code that renders output ...
     *   Symbols.setDebug(false);
     * </pre>
     */
    public static void setDebug(boolean enabled) {
        debugMode = enabled;
    }

    public static boolean isDebug() { return debugMode; }

    // ── Arrows ────────────────────────────────────────────────────────────────

    /** Up arrow: {@code ↑} / {@code ^} */
    public String up()    { return unicode ? "↑" : "^"; }

    /** Down arrow: {@code ↓} / {@code v} */
    public String down()  { return unicode ? "↓" : "v"; }

    /** Right arrow: {@code →} / {@code ->} */
    public String right() { return unicode ? "→" : "->"; }

    /** Left arrow: {@code ←} / {@code <-} */
    public String left()  { return unicode ? "←" : "<-"; }

    // ── Separators ────────────────────────────────────────────────────────────

    /** En dash separator with spaces: {@code " – "} / {@code " - "} */
    public String dashSep()  { return unicode ? " – " : " - "; }

    /** En dash (bare): {@code "–"} / {@code "-"} */
    public String dash()     { return unicode ? "–" : "-"; }

    /** Horizontal bar (for dividers): {@code "─"} / {@code "-"} */
    public String hbar()     { return unicode ? "─" : "-"; }

    /** Horizontal bar repeated n times. */
    public String hbar(int n) { return (unicode ? "─" : "-").repeat(n); }

    // ── List markers ──────────────────────────────────────────────────────────

    /** Filled bullet: {@code "•"} / {@code "*"} */
    public String bullet()    { return unicode ? "•" : "*"; }

    /** Open bullet (sub-list): {@code "◦"} / {@code "o"} */
    public String subBullet() { return unicode ? "◦" : "o"; }

    // ── Math / misc ───────────────────────────────────────────────────────────

    /** Delta symbol: {@code "Δ"} / {@code "d"} */
    public String delta() { return unicode ? "Δ" : "d"; }

    /** Box-drawing vertical bar: {@code "│"} / {@code "|"} */
    public String vbar() { return unicode ? "│" : "|"; }

    /** Box-drawing top-left corner: {@code "┌"} / {@code "+"} */
    public String cornerTL() { return unicode ? "┌" : "+"; }

    // ── Template rendering ─────────────────────────────────────────────────────

    /**
     * Resolves symbol placeholders in {@code template}, applies
     * {@link String#format(String, Object...)} with the given {@code args},
     * then — in ASCII mode — calls {@link #sanitize} to strip any remaining
     * non-ASCII characters that arrived through format arguments.
     *
     * <p>Placeholder syntax: {@code {X}} where {@code X} is the Unicode character
     * for the symbol. Examples:
     * <ul>
     *   <li>{@code {↑}} → {@link #up()}</li>
     *   <li>{@code {↓}} → {@link #down()}</li>
     *   <li>{@code {→}} → {@link #right()}</li>
     *   <li>{@code {←}} → {@link #left()}</li>
     *   <li>{@code {–}} → {@link #dash()}</li>
     *   <li>{@code {─}} → {@link #hbar()}</li>
     *   <li>{@code {•}} → {@link #bullet()}</li>
     *   <li>{@code {◦}} → {@link #subBullet()}</li>
     *   <li>{@code {Δ}} → {@link #delta()}</li>
     *   <li>{@code {│}} → {@link #vbar()}</li>
     * </ul>
     *
     * <p>If there are no {@code args}, skips the {@code String.format} call.
     */
    public String fmt(String template, Object... args) {
        String resolved = resolve(template);
        String formatted = args.length == 0 ? resolved : String.format(resolved, args);
        return unicode ? formatted : sanitize(formatted);
    }

    /**
     * Resolves only the symbol placeholders in a template string — does NOT call
     * {@link String#format} and does NOT sanitize. Use this when you want to keep
     * {@code %s}/{@code %d} specifiers intact for a later {@code String.format} call,
     * or when building a format string that will be used by something else.
     * For final output, prefer {@link #fmt}.
     */
    public String resolve(String template) {
        if (unicode) {
            // In Unicode mode placeholders expand to the character itself
            if (template.indexOf('{') < 0) return template;
            return template
                .replace("{↑}", "↑").replace("{↓}", "↓").replace("{→}", "→").replace("{←}", "←")
                .replace("{–}", "–").replace("{─}", "─").replace("{•}", "•").replace("{◦}", "◦")
                .replace("{Δ}", "Δ").replace("{│}", "│").replace("{┌}", "┌");
        }
        // ASCII mode: expand each placeholder to its ASCII substitute
        if (template.indexOf('{') < 0) return template;
        return template
            .replace("{↑}", "^").replace("{↓}", "v").replace("{→}", "->").replace("{←}", "<-")
            .replace("{–}", "-").replace("{─}", "-").replace("{•}", "*").replace("{◦}", "o")
            .replace("{Δ}", "d").replace("{│}", "|").replace("{┌}", "+");
    }

    /**
     * Sanitizes {@code s} so the result is guaranteed pure ASCII (code points 0–127).
     *
     * <p>The method works in two passes:
     * <ol>
     *   <li>Apply the full {@link #SUBSTITUTIONS} table, replacing every known
     *       non-ASCII character with its ASCII equivalent.</li>
     *   <li>Scan what remains character-by-character; any code point {@code > 127}
     *       that was not covered by the table is replaced with {@code ?}.</li>
     * </ol>
     *
     * <p>In Unicode mode this method is a no-op (returns {@code s} unchanged).
     * In ASCII mode it is automatically applied by {@link #fmt} — call it explicitly
     * only when you have a pre-built string that was not produced through {@link #fmt}.
     *
     * <p>When {@link #isDebug()} is {@code true}, every character replaced by {@code ?}
     * in pass 2 triggers a warning on {@code System.err} showing the Unicode code point
     * and the surrounding context, helping identify gaps in the substitution table.
     */
    public String sanitize(String s) {
        if (unicode || s == null) return s;

        // Pass 1: apply known substitutions
        String result = s;
        for (String[] sub : SUBSTITUTIONS) {
            if (result.indexOf(sub[0].charAt(0)) >= 0) {
                result = result.replace(sub[0], sub[1]);
            }
        }

        // Fast path: check if all remaining chars are ASCII
        boolean clean = true;
        for (int i = 0; i < result.length(); i++) {
            if (result.charAt(i) > 127) { clean = false; break; }
        }
        if (clean) return result;

        // Pass 2: replace any remaining non-ASCII with '?', optionally logging them
        StringBuilder sb = new StringBuilder(result.length());
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c > 127) {
                if (debugMode) {
                    int start = Math.max(0, i - 10);
                    int end   = Math.min(result.length(), i + 11);
                    System.err.printf(
                        "[Symbols] WARNING: unmapped non-ASCII U+%04X '%s' at index %d in: ...%s...%n",
                        (int) c, c, i, result.substring(start, end));
                }
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public boolean isUnicode() { return unicode; }
}
