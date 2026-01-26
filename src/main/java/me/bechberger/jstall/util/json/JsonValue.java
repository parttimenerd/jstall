package me.bechberger.jstall.util.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sealed interface representing JSON values
 */
public sealed interface JsonValue {

    /**
     * Assert that this value is a JsonObject and return it.
     * @throws IllegalStateException if this is not a JsonObject
     */
    default JsonObject asObject() {
        if (this instanceof JsonObject obj) {
            return obj;
        }
        throw new IllegalStateException("Expected JsonObject but got " + this.getClass().getSimpleName());
    }

    /**
     * Assert that this value is a JsonArray and return it.
     * @throws IllegalStateException if this is not a JsonArray
     */
    default JsonArray asArray() {
        if (this instanceof JsonArray arr) {
            return arr;
        }
        throw new IllegalStateException("Expected JsonArray but got " + this.getClass().getSimpleName());
    }

    /**
     * Assert that this value is a JsonString and return it.
     * @throws IllegalStateException if this is not a JsonString
     */
    default JsonString asJsonString() {
        if (this instanceof JsonString str) {
            return str;
        }
        throw new IllegalStateException("Expected JsonString but got " + this.getClass().getSimpleName());
    }

    /**
     * Assert that this value is a JsonString and return its string value.
     * @throws IllegalStateException if this is not a JsonString
     */
    default String asString() {
        return asJsonString().value();
    }

    /**
     * Assert that this value is a JsonNumber and return it.
     * @throws IllegalStateException if this is not a JsonNumber
     */
    default JsonNumber asJsonNumber() {
        if (this instanceof JsonNumber num) {
            return num;
        }
        throw new IllegalStateException("Expected JsonNumber but got " + this.getClass().getSimpleName());
    }

    /**
     * Assert that this value is a JsonNumber and return its double value.
     * @throws IllegalStateException if this is not a JsonNumber
     */
    default double asDouble() {
        return asJsonNumber().value();
    }

    /**
     * Assert that this value is a JsonNumber and return its int value.
     * @throws IllegalStateException if this is not a JsonNumber
     */
    default int asInt() {
        return asJsonNumber().intValue();
    }

    /**
     * Assert that this value is a JsonNumber and return its long value.
     * @throws IllegalStateException if this is not a JsonNumber
     */
    default long asLong() {
        return asJsonNumber().longValue();
    }

    /**
     * Assert that this value is a JsonBoolean and return it.
     * @throws IllegalStateException if this is not a JsonBoolean
     */
    default JsonBoolean asJsonBoolean() {
        if (this instanceof JsonBoolean bool) {
            return bool;
        }
        throw new IllegalStateException("Expected JsonBoolean but got " + this.getClass().getSimpleName());
    }

    /**
     * Assert that this value is a JsonBoolean and return its boolean value.
     * @throws IllegalStateException if this is not a JsonBoolean
     */
    default boolean asBoolean() {
        return asJsonBoolean().value();
    }

    /**
     * Assert that this value is a JsonNull.
     * @throws IllegalStateException if this is not a JsonNull
     */
    default JsonNull asNull() {
        if (this instanceof JsonNull n) {
            return n;
        }
        throw new IllegalStateException("Expected JsonNull but got " + this.getClass().getSimpleName());
    }

    /**
     * Check if this value is a JsonObject.
     */
    default boolean isObject() {
        return this instanceof JsonObject;
    }

    /**
     * Check if this value is a JsonArray.
     */
    default boolean isArray() {
        return this instanceof JsonArray;
    }

    /**
     * Check if this value is a JsonString.
     */
    default boolean isString() {
        return this instanceof JsonString;
    }

    /**
     * Check if this value is a JsonNumber.
     */
    default boolean isNumber() {
        return this instanceof JsonNumber;
    }

    /**
     * Check if this value is a JsonBoolean.
     */
    default boolean isBoolean() {
        return this instanceof JsonBoolean;
    }

    /**
     * Check if this value is a JsonNull.
     */
    default boolean isNull() {
        return this instanceof JsonNull;
    }

    record JsonObject(Map<String, JsonValue> fields) implements JsonValue {
        /**
         * Create an empty JSON object
         */
        public JsonObject() {
            this(new LinkedHashMap<>());
        }

        /**
         * Create a JSON object from varargs key-value pairs
         */
        public static JsonObject of(Object... keyValuePairs) {
            if (keyValuePairs.length % 2 != 0) {
                throw new IllegalArgumentException("Must provide even number of arguments (key-value pairs)");
            }
            Map<String, JsonValue> map = new LinkedHashMap<>();
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                map.put((String) keyValuePairs[i], (JsonValue) keyValuePairs[i + 1]);
            }
            return new JsonObject(map);
        }

        /**
         * Add or update a field in this object (returns new instance)
         */
        public JsonObject put(String key, JsonValue value) {
            Map<String, JsonValue> newFields = new LinkedHashMap<>(fields);
            newFields.put(key, value);
            return new JsonObject(newFields);
        }

        /**
         * Get a field value
         */
        public JsonValue get(String key) {
            return fields.get(key);
        }

        /**
         * Check if a field exists
         */
        public boolean has(String key) {
            return fields.containsKey(key);
        }

        /**
         * Remove a field (returns new instance)
         */
        public JsonObject remove(String key) {
            Map<String, JsonValue> newFields = new LinkedHashMap<>(fields);
            newFields.remove(key);
            return new JsonObject(newFields);
        }

        /**
         * Map over the values in this object
         */
        public JsonObject mapValues(Function<JsonValue, JsonValue> mapper) {
            Map<String, JsonValue> mapped = fields.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> mapper.apply(e.getValue()),
                    (a, b) -> b,
                    LinkedHashMap::new
                ));
            return new JsonObject(mapped);
        }

        /**
         * Get the size of this object
         */
        public int size() {
            return fields.size();
        }

        /**
         * Check if this object is empty
         */
        public boolean isEmpty() {
            return fields.isEmpty();
        }
    }

    record JsonArray(List<JsonValue> elements) implements JsonValue {
        /**
         * Create an empty JSON array
         */
        public JsonArray() {
            this(new ArrayList<>());
        }

        /**
         * Create a JSON array from varargs elements
         */
        public static JsonArray of(JsonValue... elements) {
            return new JsonArray(List.of(elements));
        }

        /**
         * Add an element to this array (returns new instance)
         */
        public JsonArray add(JsonValue element) {
            List<JsonValue> newElements = new ArrayList<>(elements);
            newElements.add(element);
            return new JsonArray(newElements);
        }

        /**
         * Add an element at a specific index (returns new instance)
         */
        public JsonArray add(int index, JsonValue element) {
            List<JsonValue> newElements = new ArrayList<>(elements);
            newElements.add(index, element);
            return new JsonArray(newElements);
        }

        /**
         * Get an element at an index
         */
        public JsonValue get(int index) {
            return elements.get(index);
        }

        /**
         * Set an element at an index (returns new instance)
         */
        public JsonArray set(int index, JsonValue element) {
            List<JsonValue> newElements = new ArrayList<>(elements);
            newElements.set(index, element);
            return new JsonArray(newElements);
        }

        /**
         * Remove an element at an index (returns new instance)
         */
        public JsonArray remove(int index) {
            List<JsonValue> newElements = new ArrayList<>(elements);
            newElements.remove(index);
            return new JsonArray(newElements);
        }

        /**
         * Map over the elements in this array
         */
        public JsonArray map(Function<JsonValue, JsonValue> mapper) {
            List<JsonValue> mapped = elements.stream()
                .map(mapper)
                .collect(Collectors.toList());
            return new JsonArray(mapped);
        }

        /**
         * Filter elements in this array
         */
        public JsonArray filter(Function<JsonValue, Boolean> predicate) {
            List<JsonValue> filtered = elements.stream()
                .filter(predicate::apply)
                .collect(Collectors.toList());
            return new JsonArray(filtered);
        }

        /**
         * Get the size of this array
         */
        public int size() {
            return elements.size();
        }

        /**
         * Check if this array is empty
         */
        public boolean isEmpty() {
            return elements.isEmpty();
        }
    }

    record JsonString(String value) implements JsonValue {
        /**
         * Map the string value
         */
        public JsonString map(Function<String, String> mapper) {
            return new JsonString(mapper.apply(value));
        }
    }

    record JsonNumber(double value) implements JsonValue {
        /**
         * Map the numeric value
         */
        public JsonNumber map(Function<Double, Double> mapper) {
            return new JsonNumber(mapper.apply(value));
        }

        /**
         * Get the value as an int
         */
        public int intValue() {
            return (int) value;
        }

        /**
         * Get the value as a long
         */
        public long longValue() {
            return (long) value;
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {
        /**
         * Negate this boolean value
         */
        public JsonBoolean negate() {
            return new JsonBoolean(!value);
        }
    }

    record JsonNull() implements JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();
    }
}