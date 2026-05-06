package me.bechberger.jstall.cli.live;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Low-level Unix terminal control without JLine.
 * Uses stty for raw mode, /dev/tty for input, ANSI sequences for rendering.
 */
public class RawTerminal implements AutoCloseable {

    private static final String ALT_SCREEN_ON = "\033[?1049h";
    private static final String ALT_SCREEN_OFF = "\033[?1049l";
    private static final String CURSOR_HIDE = "\033[?25l";
    private static final String CURSOR_SHOW = "\033[?25h";
    private static final String CURSOR_HOME = "\033[H";
    private static final String CLEAR_LINE = "\033[K";
    private static final String CLEAR_BELOW = "\033[J";

    private String savedSttySettings;
    private FileInputStream ttyInput;
    private final PrintStream out;
    private volatile int termRows = 24;
    private volatile int termCols = 80;
    private boolean rawMode = false;

    public RawTerminal() {
        this.out = System.out;
    }

    /**
     * Enters raw mode: switches to alternate screen, hides cursor, enables raw input.
     */
    public void enter() throws IOException {
        // Save current stty settings
        savedSttySettings = stty("-g").trim();

        // Open /dev/tty for raw key reading
        ttyInput = new FileInputStream("/dev/tty");

        // Enter raw mode: no echo, no line buffering, read 1 char at a time
        stty("-echo", "-icanon", "min", "1", "time", "0");
        rawMode = true;

        // Switch to alternate screen buffer and hide cursor
        out.print(ALT_SCREEN_ON + CURSOR_HIDE);
        out.flush();

        // Detect initial terminal size
        refreshSize();

        // Install SIGWINCH handler for resize detection
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("WINCH"), sig -> refreshSize());
        } catch (IllegalArgumentException ignored) {
            // Signal not available on this platform
        }
    }

    /**
     * Reads a key event with timeout. Returns null if no key pressed within timeout.
     */
    public KeyEvent readKey(long timeoutMs) throws IOException {
        if (ttyInput == null) return null;

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ttyInput.available() > 0) {
                int b = ttyInput.read();
                if (b == -1) return null;
                return decodeKey(b);
            }
            // Brief sleep to avoid busy-waiting
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private KeyEvent decodeKey(int firstByte) throws IOException {
        return switch (firstByte) {
            case 9 -> KeyEvent.TAB;
            case 10, 13 -> KeyEvent.ENTER;
            case 27 -> decodeEscapeSequence();
            case 127 -> KeyEvent.BACKSPACE;
            case 8 -> KeyEvent.BACKSPACE;
            default -> {
                if (firstByte >= 32 && firstByte < 127) {
                    yield KeyEvent.ofChar((char) firstByte);
                }
                yield new KeyEvent.Special(KeyEvent.Type.UNKNOWN);
            }
        };
    }

    private KeyEvent decodeEscapeSequence() throws IOException {
        // Wait briefly for more bytes (escape sequences come in bursts)
        long start = System.currentTimeMillis();
        while (ttyInput.available() == 0 && System.currentTimeMillis() - start < 50) {
            try { Thread.sleep(1); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return KeyEvent.ESCAPE;
            }
        }
        if (ttyInput.available() == 0) {
            return KeyEvent.ESCAPE; // Standalone Escape key
        }

        int second = ttyInput.read();
        if (second == '[') {
            return decodeCsiSequence();
        }
        // Alt+key or unknown
        return new KeyEvent.Special(KeyEvent.Type.UNKNOWN);
    }

    private KeyEvent decodeCsiSequence() throws IOException {
        // Read until we get a letter (the final byte of CSI sequences)
        StringBuilder buf = new StringBuilder();
        long deadline = System.currentTimeMillis() + 50; // Wait up to 50ms for sequence bytes
        while (System.currentTimeMillis() < deadline) {
            if (ttyInput.available() == 0) {
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            int b = ttyInput.read();
            if (b >= 0x40 && b <= 0x7E) {
                // Final byte
                return mapCsiSequence(buf.toString(), (char) b);
            }
            buf.append((char) b);
        }
        return new KeyEvent.Special(KeyEvent.Type.UNKNOWN);
    }

    private KeyEvent mapCsiSequence(String params, char finalByte) {
        return switch (finalByte) {
            case 'A' -> KeyEvent.UP;
            case 'B' -> KeyEvent.DOWN;
            case 'C' -> KeyEvent.RIGHT;
            case 'D' -> KeyEvent.LEFT;
            case 'H' -> KeyEvent.HOME;
            case 'F' -> KeyEvent.END;
            case 'Z' -> KeyEvent.SHIFT_TAB;
            case '~' -> switch (params) {
                case "5" -> KeyEvent.PAGE_UP;
                case "6" -> KeyEvent.PAGE_DOWN;
                case "1" -> KeyEvent.HOME;
                case "4" -> KeyEvent.END;
                case "3" -> new KeyEvent.Special(KeyEvent.Type.DELETE);
                default -> new KeyEvent.Special(KeyEvent.Type.UNKNOWN);
            };
            default -> new KeyEvent.Special(KeyEvent.Type.UNKNOWN);
        };
    }

    /**
     * Returns current terminal rows.
     */
    public int getRows() {
        return termRows;
    }

    /**
     * Returns current terminal columns.
     */
    public int getCols() {
        return termCols;
    }

    /**
     * Moves cursor to home position.
     */
    public void cursorHome() {
        out.print(CURSOR_HOME);
    }

    /**
     * Clears from cursor to end of line.
     */
    public void clearLine() {
        out.print(CLEAR_LINE);
    }

    /**
     * Clears from cursor to end of screen.
     */
    public void clearBelow() {
        out.print(CLEAR_BELOW);
    }

    /**
     * Writes a line at the current cursor position (clears remainder of line).
     */
    public void writeLine(String text) {
        // Truncate to terminal width based on visible length (excluding ANSI escapes)
        int visibleLen = visibleLength(text);
        if (visibleLen > termCols) {
            text = truncateVisible(text, termCols - 1) + "…";
        }
        out.print(text + CLEAR_LINE + "\n");
    }

    /**
     * Returns the visible length of a string (excluding ANSI escape sequences).
     */
    private static int visibleLength(String s) {
        return s.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "").length();
    }

    /**
     * Truncates a string to maxVisible visible characters, preserving ANSI sequences.
     */
    private static String truncateVisible(String s, int maxVisible) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        int i = 0;
        while (i < s.length() && visible < maxVisible) {
            if (s.charAt(i) == '\033' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                // Skip the entire escape sequence
                int start = i;
                i += 2;
                while (i < s.length() && !Character.isLetter(s.charAt(i))) i++;
                if (i < s.length()) i++; // include the final letter
                result.append(s, start, i);
            } else {
                result.append(s.charAt(i));
                visible++;
                i++;
            }
        }
        // Append any reset sequences to avoid leaking styles
        result.append("\033[0m");
        return result.toString();
    }

    /**
     * Writes text without newline.
     */
    public void write(String text) {
        out.print(text);
    }

    /**
     * Flushes output.
     */
    public void flush() {
        out.flush();
    }

    /**
     * Moves cursor to specific row (1-indexed).
     */
    public void moveTo(int row, int col) {
        out.print("\033[" + row + ";" + col + "H");
    }

    /**
     * Refreshes terminal size by querying stty.
     */
    private void refreshSize() {
        try {
            String size = stty("size").trim();
            String[] parts = size.split("\\s+");
            if (parts.length == 2) {
                termRows = Integer.parseInt(parts[0]);
                termCols = Integer.parseInt(parts[1]);
            }
        } catch (Exception ignored) {
            // Keep previous size
        }
    }

    @Override
    public void close() {
        if (rawMode) {
            // Restore terminal
            out.print(CURSOR_SHOW + ALT_SCREEN_OFF);
            out.flush();
            if (savedSttySettings != null) {
                try {
                    stty(savedSttySettings);
                } catch (IOException ignored) {}
            }
            rawMode = false;
        }
        if (ttyInput != null) {
            try { ttyInput.close(); } catch (IOException ignored) {}
            ttyInput = null;
        }
    }

    /**
     * Returns true if the current environment supports interactive terminal mode.
     */
    public static boolean isInteractiveSupported() {
        if (System.console() == null) return false;
        // Check if /dev/tty is available (Unix)
        return new File("/dev/tty").exists();
    }

    private static String stty(String... args) throws IOException {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "stty";
        System.arraycopy(args, 0, cmd, 1, args.length);
        cmd[cmd.length - 1] = "< /dev/tty"; // dummy, handled below

        // Use sh -c to redirect /dev/tty
        StringBuilder sttyCmd = new StringBuilder("stty");
        for (String arg : args) {
            sttyCmd.append(' ').append(arg);
        }
        sttyCmd.append(" < /dev/tty");

        Process proc = new ProcessBuilder("sh", "-c", sttyCmd.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
        try {
            proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return output;
    }
}
