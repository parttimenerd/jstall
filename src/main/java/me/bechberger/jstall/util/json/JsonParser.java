package me.bechberger.jstall.util.json;

import me.bechberger.jstall.util.json.JsonValue.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple JSON parser
 */
public class JsonParser {
    private final String input;
    private int pos = 0;

    public JsonParser(String input) {
        this.input = input;
    }

    public static JsonValue parse(String json) {
        return new JsonParser(json).parseValue();
    }

    private JsonValue parseValue() {
        skipWhitespace();
        if (pos >= input.length()) {
            throw new JsonParseException("Unexpected end of input");
        }

        char c = input.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield parseNumber();
                }
                throw new JsonParseException("Unexpected character: " + c);
            }
        };
    }

    private JsonObject parseObject() {
        expect('{');
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        skipWhitespace();

        if (peek() == '}') {
            pos++;
            return new JsonObject(fields);
        }

        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonParseException("Expected string key");
            }
            String key = parseString().value();
            skipWhitespace();
            expect(':');
            JsonValue value = parseValue();
            fields.put(key, value);

            skipWhitespace();
            char next = peek();
            if (next == '}') {
                pos++;
                break;
            } else if (next == ',') {
                pos++;
            } else {
                throw new JsonParseException("Expected ',' or '}'");
            }
        }

        return new JsonObject(fields);
    }

    private JsonArray parseArray() {
        expect('[');
        List<JsonValue> elements = new ArrayList<>();
        skipWhitespace();

        if (peek() == ']') {
            pos++;
            return new JsonArray(elements);
        }

        while (true) {
            elements.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ']') {
                pos++;
                break;
            } else if (next == ',') {
                pos++;
            } else {
                throw new JsonParseException("Expected ',' or ']'");
            }
        }

        return new JsonArray(elements);
    }

    private JsonString parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();

        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') {
                return new JsonString(sb.toString());
            } else if (c == '\\') {
                if (pos >= input.length()) {
                    throw new JsonParseException("Unexpected end in string escape");
                }
                char escaped = input.charAt(pos++);
                sb.append(switch (escaped) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> parseUnicodeEscape();
                    default -> throw new JsonParseException("Invalid escape: \\" + escaped);
                });
            } else {
                sb.append(c);
            }
        }

        throw new JsonParseException("Unterminated string");
    }

    private char parseUnicodeEscape() {
        if (pos + 4 > input.length()) {
            throw new JsonParseException("Invalid unicode escape");
        }
        String hex = input.substring(pos, pos + 4);
        pos += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private JsonNumber parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }

        if (!Character.isDigit(peek())) {
            throw new JsonParseException("Invalid number");
        }

        while (pos < input.length() && Character.isDigit(peek())) {
            pos++;
        }

        if (pos < input.length() && peek() == '.') {
            pos++;
            if (!Character.isDigit(peek())) {
                throw new JsonParseException("Invalid number");
            }
            while (pos < input.length() && Character.isDigit(peek())) {
                pos++;
            }
        }

        if (pos < input.length() && (peek() == 'e' || peek() == 'E')) {
            pos++;
            if (pos < input.length() && (peek() == '+' || peek() == '-')) {
                pos++;
            }
            if (!Character.isDigit(peek())) {
                throw new JsonParseException("Invalid number");
            }
            while (pos < input.length() && Character.isDigit(peek())) {
                pos++;
            }
        }

        String numStr = input.substring(start, pos);
        return new JsonNumber(Double.parseDouble(numStr));
    }

    private JsonBoolean parseBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return new JsonBoolean(true);
        } else if (input.startsWith("false", pos)) {
            pos += 5;
            return new JsonBoolean(false);
        }
        throw new JsonParseException("Invalid boolean");
    }

    private JsonNull parseNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return JsonNull.INSTANCE;
        }
        throw new JsonParseException("Invalid null");
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= input.length()) {
            throw new JsonParseException("Unexpected end of input");
        }
        return input.charAt(pos);
    }

    private void expect(char expected) {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != expected) {
            throw new JsonParseException("Expected '" + expected + "'");
        }
        pos++;
    }

    public static class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }
}