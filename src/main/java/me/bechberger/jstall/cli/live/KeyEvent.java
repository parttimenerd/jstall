package me.bechberger.jstall.cli.live;

/**
 * Represents a parsed keyboard event from raw terminal input.
 */
public sealed interface KeyEvent {

    record Char(char ch) implements KeyEvent {}
    record Special(Type type) implements KeyEvent {}

    enum Type {
        UP, DOWN, LEFT, RIGHT,
        PAGE_UP, PAGE_DOWN,
        HOME, END,
        TAB, SHIFT_TAB,
        ENTER, ESCAPE, BACKSPACE, DELETE,
        UNKNOWN
    }

    // Common instances for convenience
    KeyEvent UP = new Special(Type.UP);
    KeyEvent DOWN = new Special(Type.DOWN);
    KeyEvent LEFT = new Special(Type.LEFT);
    KeyEvent RIGHT = new Special(Type.RIGHT);
    KeyEvent PAGE_UP = new Special(Type.PAGE_UP);
    KeyEvent PAGE_DOWN = new Special(Type.PAGE_DOWN);
    KeyEvent HOME = new Special(Type.HOME);
    KeyEvent END = new Special(Type.END);
    KeyEvent TAB = new Special(Type.TAB);
    KeyEvent SHIFT_TAB = new Special(Type.SHIFT_TAB);
    KeyEvent ENTER = new Special(Type.ENTER);
    KeyEvent ESCAPE = new Special(Type.ESCAPE);
    KeyEvent BACKSPACE = new Special(Type.BACKSPACE);

    static KeyEvent ofChar(char ch) {
        return new Char(ch);
    }
}
