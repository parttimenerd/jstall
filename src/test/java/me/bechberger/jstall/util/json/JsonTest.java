package me.bechberger.jstall.util.json;

import me.bechberger.jstall.util.json.JsonValue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void testParseAndPrintObject() {
        String json = """
            {
              "name": "John",
              "age": 30,
              "active": true,
              "address": null
            }
            """;

        JsonValue parsed = JsonParser.parse(json);
        assertThat(parsed).isInstanceOf(JsonObject.class);

        JsonObject obj = (JsonObject) parsed;
        assertThat(obj.fields()).hasSize(4);
        assertThat(obj.fields().get("name")).isEqualTo(new JsonString("John"));
        assertThat(obj.fields().get("age")).isEqualTo(new JsonNumber(30));
        assertThat(obj.fields().get("active")).isEqualTo(new JsonBoolean(true));
        assertThat(obj.fields().get("address")).isEqualTo(JsonNull.INSTANCE);

        String printed = JsonPrinter.print(parsed);
        assertThat(printed).contains("\"name\": \"John\"");
        assertThat(printed).contains("\"age\": 30");
    }

    @Test
    void testParseAndPrintArray() {
        String json = "[1, 2, 3, \"test\", false]";

        JsonValue parsed = JsonParser.parse(json);
        assertThat(parsed).isInstanceOf(JsonArray.class);

        JsonArray arr = (JsonArray) parsed;
        assertThat(arr.elements()).hasSize(5);
        assertThat(arr.elements().get(0)).isEqualTo(new JsonNumber(1));
        assertThat(arr.elements().get(3)).isEqualTo(new JsonString("test"));
        assertThat(arr.elements().get(4)).isEqualTo(new JsonBoolean(false));
    }

    @Test
    void testParseNestedStructure() {
        String json = """
            {
              "users": [
                {"name": "Alice", "age": 25},
                {"name": "Bob", "age": 35}
              ],
              "count": 2
            }
            """;

        JsonValue parsed = JsonParser.parse(json);
        JsonObject obj = (JsonObject) parsed;

        JsonArray users = (JsonArray) obj.fields().get("users");
        assertThat(users.elements()).hasSize(2);

        JsonObject firstUser = (JsonObject) users.elements().getFirst();
        assertThat(firstUser.fields().get("name")).isEqualTo(new JsonString("Alice"));
    }

    @Test
    void testStringEscaping() {
        String json = "\"Hello\\nWorld\\t!\\\"\"";
        JsonString parsed = (JsonString) JsonParser.parse(json);
        assertThat(parsed.value()).isEqualTo("Hello\nWorld\t!\"");

        String printed = JsonPrinter.print(parsed);
        assertThat(printed).isEqualTo("\"Hello\\nWorld\\t!\\\"\"");
    }

    @Test
    void testPrintCompact() {
        JsonObject obj = new JsonObject(Map.of(
            "name", new JsonString("test"),
            "value", new JsonNumber(42)
        ));

        String compact = JsonPrinter.printCompact(obj);
        assertThat(compact).doesNotContain("\n");
        assertThat(compact).contains("{\"");
    }

    @Test
    void testEmptyObjectAndArray() {
        assertThat(JsonParser.parse("{}")).isEqualTo(new JsonObject(Map.of()));
        assertThat(JsonParser.parse("[]")).isEqualTo(new JsonArray(List.of()));
    }

    @Test
    void testNumberFormats() {
        assertThat(JsonParser.parse("42")).isEqualTo(new JsonNumber(42));
        assertThat(JsonParser.parse("3.14")).isEqualTo(new JsonNumber(3.14));
        assertThat(JsonParser.parse("-10")).isEqualTo(new JsonNumber(-10));
        assertThat(JsonParser.parse("1.5e10")).isEqualTo(new JsonNumber(1.5e10));
    }

    @Test
    void testInvalidJson() {
        assertThrows(JsonParser.JsonParseException.class, () -> JsonParser.parse("{invalid}"));
        assertThrows(JsonParser.JsonParseException.class, () -> JsonParser.parse("[1, 2,]"));
        assertThrows(JsonParser.JsonParseException.class, () -> JsonParser.parse("\"unterminated"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Primitives
        "null",
        "true",
        "false",
        "0",
        "42",
        "-123",
        "3.14159",
        "-0.5",
        "1e10",
        "1.5e-5",
        "\"\"",
        "\"simple\"",

        // Strings with special characters
        "\"Hello, World!\"",
        "\"line1\\nline2\"",
        "\"tab\\there\"",
        "\"quote: \\\"test\\\"\"",
        "\"backslash: \\\\\"",
        "\"unicode: \\u0041\\u0042\\u0043\"",

        // Empty containers
        "{}",
        "[]",

        // Simple arrays
        "[1, 2, 3]",
        "[true, false, null]",
        "[\"a\", \"b\", \"c\"]",

        // Simple objects
        "{\"key\": \"value\"}",
        "{\"a\": 1, \"b\": 2}",
        "{\"null\": null, \"bool\": true, \"num\": 42}",

        // Nested structures
        "{\"array\": [1, 2, 3]}",
        "{\"object\": {\"nested\": true}}",
        "[{\"a\": 1}, {\"b\": 2}]",
        "[[1, 2], [3, 4]]",

        // Complex nested
        "{\"users\": [{\"name\": \"Alice\", \"age\": 25}, {\"name\": \"Bob\", \"age\": 35}], \"count\": 2}",
        "{\"data\": {\"items\": [1, 2, 3], \"meta\": {\"total\": 3}}}",
        "[{\"nested\": [{\"deep\": [1, 2, 3]}]}]",

        // Edge cases with whitespace variations (parser should handle these)
        "  {  \"key\"  :  \"value\"  }  ",
        "[\n  1,\n  2,\n  3\n]",

        // Numbers edge cases
        "0.0",
        "1234567890",
        "0.123456789",

        // Mixed arrays
        "[1, \"two\", true, null, {\"five\": 5}, [6, 7]]",

        // Objects with various value types
        "{\"str\": \"text\", \"num\": 123, \"bool\": false, \"null\": null, \"arr\": [], \"obj\": {}}",

        // Deep nesting
        "{\"a\": {\"b\": {\"c\": {\"d\": \"deep\"}}}}",
        "[[[[\"deep\"]]]]"
    })
    void testRoundTrip(String json) {
        // Parse the JSON
        JsonValue parsed = JsonParser.parse(json);

        // Print it back (compact to avoid whitespace differences)
        String printed = JsonPrinter.printCompact(parsed);

        // Parse again
        JsonValue reparsed = JsonParser.parse(printed);

        // The two parsed values should be equal
        assertThat(reparsed).isEqualTo(parsed);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Pretty print roundtrip - test that pretty-printed JSON can be parsed back
        "{\"key\": \"value\"}",
        "[1, 2, 3]",
        "{\"nested\": {\"array\": [1, 2, 3]}}",
        "{\"users\": [{\"name\": \"Alice\"}, {\"name\": \"Bob\"}]}"
    })
    void testPrettyPrintRoundTrip(String json) {
        JsonValue parsed = JsonParser.parse(json);
        String prettyPrinted = JsonPrinter.print(parsed);
        JsonValue reparsed = JsonParser.parse(prettyPrinted);
        assertThat(reparsed).isEqualTo(parsed);
    }
}