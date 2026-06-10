package me.bechberger.jstall.util.render;

/** ANSI escape code constants shared by terminal renderers and live mode. */
public final class AnsiCodes {

    private AnsiCodes() {}

    // Text attributes
    public static final String RESET = "\033[0m";
    public static final String BOLD_ON = "\033[1m";
    public static final String DIM_ON = "\033[2m";
    public static final String ITALIC_ON = "\033[3m";
    public static final String UNDERLINE_ON = "\033[4m";
    public static final String INVERSE_ON = "\033[7m";
    public static final String INVERSE_OFF = "\033[27m";

    // Foreground colors
    public static final String FG_RED = "\033[31m";
    public static final String FG_GREEN = "\033[32m";
    public static final String FG_YELLOW = "\033[33m";
    public static final String FG_BLUE = "\033[34m";
    public static final String FG_MAGENTA = "\033[35m";
    public static final String FG_CYAN = "\033[36m";
    public static final String FG_WHITE = "\033[37m";
    public static final String FG_DEFAULT = "\033[39m";

    // Cursor / screen control (used by live mode)
    public static final String ALT_SCREEN_ON = "\033[?1049h";
    public static final String ALT_SCREEN_OFF = "\033[?1049l";
    public static final String CURSOR_HIDE = "\033[?25l";
    public static final String CURSOR_SHOW = "\033[?25h";
    public static final String CURSOR_HOME = "\033[H";
    public static final String CLEAR_LINE = "\033[K";
    public static final String CLEAR_BELOW = "\033[J";
    public static final String CLEAR_SCREEN = "\033[2J\033[H";

    /** Strip all ANSI escape sequences from a string (for length calculations). */
    public static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }

    /** Visible (non-ANSI) length of a string. */
    public static int visibleLength(String s) {
        return strip(s).length();
    }
}
