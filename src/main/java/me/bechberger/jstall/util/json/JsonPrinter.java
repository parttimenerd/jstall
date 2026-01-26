package me.bechberger.jstall.util.json;

import me.bechberger.jstall.util.json.JsonValue.*;

import java.util.Map;

/**
 * Simple JSON pretty printer
 */
public class JsonPrinter {
    private final StringBuilder sb = new StringBuilder();
    private final String indent;
    private int level = 0;

    public JsonPrinter(String indent) {
        this.indent = indent;
    }

    public JsonPrinter() {
        this("  ");
    }

    public static String print(JsonValue value) {
        return new JsonPrinter().printValue(value).toString();
    }

    public static String print(JsonValue value, String indent) {
        return new JsonPrinter(indent).printValue(value).toString();
    }

    public static String printCompact(JsonValue value) {
        return new JsonPrinter("").printValue(value).toString();
    }

    public JsonPrinter printValue(JsonValue value) {
        switch (value) {
            case JsonObject obj -> printObject(obj);
            case JsonArray arr -> printArray(arr);
            case JsonString str -> printString(str);
            case JsonNumber num -> printNumber(num);
            case JsonBoolean bool -> printBoolean(bool);
            case JsonNull ignored -> sb.append("null");
        }
        return this;
    }

    private void printObject(JsonObject obj) {
        sb.append('{');
        if (!obj.fields().isEmpty()) {
            boolean first = true;
            level++;
            for (Map.Entry<String, JsonValue> entry : obj.fields().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                newline();
                printStringValue(entry.getKey());
                sb.append(':');
                if (!indent.isEmpty()) {
                    sb.append(' ');
                }
                printValue(entry.getValue());
                first = false;
            }
            level--;
            newline();
        }
        sb.append('}');
    }

    private void printArray(JsonArray arr) {
        sb.append('[');
        if (!arr.elements().isEmpty()) {
            boolean first = true;
            level++;
            for (JsonValue element : arr.elements()) {
                if (!first) {
                    sb.append(',');
                }
                newline();
                printValue(element);
                first = false;
            }
            level--;
            newline();
        }
        sb.append(']');
    }

    private void printString(JsonString str) {
        printStringValue(str.value());
    }

    private void printStringValue(String str) {
        sb.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private void printNumber(JsonNumber num) {
        double value = num.value();
        if (value == (long) value) {
            sb.append((long) value);
        } else {
            sb.append(value);
        }
    }

    private void printBoolean(JsonBoolean bool) {
        sb.append(bool.value());
    }

    private void newline() {
        if (!indent.isEmpty()) {
            sb.append('\n');
            sb.append(indent.repeat(level));
        }
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}