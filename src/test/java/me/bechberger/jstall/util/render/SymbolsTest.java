package me.bechberger.jstall.util.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class SymbolsTest {

    // ── Accessors: Unicode mode ───────────────────────────────────────────────

    @Test
    void unicodeAccessorsReturnUnicodeChars() {
        Symbols s = Symbols.UNICODE;
        assertEquals("↑", s.up());
        assertEquals("↓", s.down());
        assertEquals("→", s.right());
        assertEquals("←", s.left());
        assertEquals("–", s.dash());
        assertEquals(" – ", s.dashSep());
        assertEquals("─", s.hbar());
        assertEquals("•", s.bullet());
        assertEquals("◦", s.subBullet());
        assertEquals("Δ", s.delta());
        assertEquals("│", s.vbar());
        assertEquals("┌", s.cornerTL());
    }

    // ── Accessors: ASCII mode ─────────────────────────────────────────────────

    @Test
    void asciiAccessorsReturnAsciiOnly() {
        Symbols s = Symbols.ASCII;
        assertEquals("^",  s.up());
        assertEquals("v",  s.down());
        assertEquals("->", s.right());
        assertEquals("<-", s.left());
        assertEquals("-",  s.dash());
        assertEquals(" - ", s.dashSep());
        assertEquals("-",  s.hbar());
        assertEquals("*",  s.bullet());
        assertEquals("o",  s.subBullet());
        assertEquals("d",  s.delta());
        assertEquals("|",  s.vbar());
        assertEquals("+",  s.cornerTL());

        // Verify every accessor result is pure ASCII
        for (String v : new String[]{
                s.up(), s.down(), s.right(), s.left(), s.dash(), s.dashSep(),
                s.hbar(), s.hbar(5), s.bullet(), s.subBullet(), s.delta(),
                s.vbar(), s.cornerTL()}) {
            assertPureAscii(v);
        }
    }

    // ── Symbols.of() ─────────────────────────────────────────────────────────

    @Test
    void ofReturnsSingletons() {
        assertSame(Symbols.UNICODE, Symbols.of(true));
        assertSame(Symbols.ASCII,   Symbols.of(false));
    }

    // ── resolve() ────────────────────────────────────────────────────────────

    @Test
    void resolveExpandsPlaceholders_unicodeMode() {
        Symbols s = Symbols.UNICODE;
        assertEquals("↑ ↓ → ←", s.resolve("{↑} {↓} {→} {←}"));
        assertEquals("– ─ • ◦ Δ │ ┌", s.resolve("{–} {─} {•} {◦} {Δ} {│} {┌}"));
    }

    @Test
    void resolveExpandsPlaceholders_asciiMode() {
        Symbols s = Symbols.ASCII;
        String result = s.resolve("{↑} {↓} {→} {←} {–} {─} {•} {◦} {Δ} {│} {┌}");
        assertPureAscii(result);
        assertEquals("^ v -> <- - - * o d | +", result);
    }

    @Test
    void resolveIsNoopWhenNoPlaceholders() {
        assertEquals("plain text", Symbols.UNICODE.resolve("plain text"));
        assertEquals("plain text", Symbols.ASCII.resolve("plain text"));
    }

    // ── fmt() ────────────────────────────────────────────────────────────────

    @Test
    void fmtExpandsPlaceholdersAndFormats_unicodeMode() {
        assertEquals("heap ↑ 123m", Symbols.UNICODE.fmt("heap {↑} %s", "123m"));
    }

    @Test
    void fmtExpandsPlaceholdersAndFormats_asciiMode() {
        String result = Symbols.ASCII.fmt("heap {↑} %s", "123m");
        assertEquals("heap ^ 123m", result);
        assertPureAscii(result);
    }

    @Test
    void fmtWithNoArgsSkipsFormat() {
        assertEquals("trend ->", Symbols.ASCII.fmt("trend {→}"));
    }

    @Test
    void fmtSanitizesUnicodeInFormatArgs() {
        // A %s arg that happens to contain a Unicode arrow (e.g. from an already-computed string)
        // should be cleaned up by the sanitize pass inside fmt()
        String result = Symbols.ASCII.fmt("value: %s", "heap → 42m");
        assertPureAscii(result);
        assertEquals("value: heap -> 42m", result);
    }

    // ── sanitize() ───────────────────────────────────────────────────────────

    @Test
    void sanitizeIsNoopInUnicodeMode() {
        String s = "heap ↑ 42m";
        assertSame(s, Symbols.UNICODE.sanitize(s));
    }

    @Test
    void sanitizeHandlesNull() {
        assertNull(Symbols.ASCII.sanitize(null));
    }

    @Test
    void sanitizeReplacesKnownSymbols() {
        Symbols s = Symbols.ASCII;
        assertPureAscii(s.sanitize("↑ ↓ → ← – — ─ │ ┌ • ◦ Δ ≥ ≤ … ✅ ❌"));
    }

    @Test
    void sanitizeReplacesUnknownNonAsciiWithQuestionMark() {
        // U+00E9 'é' is not in the substitution table
        String result = Symbols.ASCII.sanitize("café");
        assertEquals("caf?", result);
    }

    @Test
    void sanitizePureAsciiInputIsReturnedUnchanged() {
        String input = "plain ASCII text 0-9 a-z A-Z !@#";
        assertSame(input, Symbols.ASCII.sanitize(input));
    }

    // ── Debug mode ───────────────────────────────────────────────────────────

    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void captureStderr() {
        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
        Symbols.setDebug(false);
    }

    @Test
    void debugModeOffByDefault_noWarningForUnknownChar() {
        assertFalse(Symbols.isDebug());
        Symbols.ASCII.sanitize("café");
        assertEquals("", capturedErr.toString());
    }

    @Test
    void debugModeOn_warnsForUnknownChar() {
        Symbols.setDebug(true);
        Symbols.ASCII.sanitize("café");
        String output = capturedErr.toString();
        assertTrue(output.contains("[Symbols] WARNING"), "expected warning, got: " + output);
        assertTrue(output.contains("U+00E9"), "expected code point, got: " + output);
    }

    @Test
    void debugModeOn_noWarningForKnownSymbol() {
        // Known symbols go through pass 1 and are replaced cleanly; pass 2 never sees them
        Symbols.setDebug(true);
        Symbols.ASCII.sanitize("heap ↑ 42m");
        assertEquals("", capturedErr.toString(), "no warning expected for known symbol ↑");
    }

    @Test
    void debugModeOn_warnsForEachUnknownChar() {
        Symbols.setDebug(true);
        Symbols.ASCII.sanitize("éñ");  // é ñ — both unknown
        String output = capturedErr.toString();
        assertTrue(output.contains("U+00E9"), "expected é warning");
        assertTrue(output.contains("U+00F1"), "expected ñ warning");
    }

    @Test
    void setDebugThenOff_suppressesWarnings() {
        Symbols.setDebug(true);
        Symbols.setDebug(false);
        Symbols.ASCII.sanitize("café");
        assertEquals("", capturedErr.toString(), "debug was turned off, no warning expected");
    }

    // ── hbar(n) ──────────────────────────────────────────────────────────────

    @Test
    void hbarRepeats() {
        assertEquals("─────", Symbols.UNICODE.hbar(5));
        assertEquals("-----", Symbols.ASCII.hbar(5));
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static void assertPureAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            assertTrue(c <= 127,
                String.format("non-ASCII char U+%04X '%s' at index %d in: %s", (int) c, c, i, s));
        }
    }
}
