package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.Objects;

/**
 * Parsed value from VM Vitals, supporting multiple representations.
 * Handles unit suffixes (k/m/g/b) and converts to different units as needed.
 * Preserves the original string for display while providing numeric access.
 */
public final class ParsedValue {
    private final String originalString;
    private final long numericValue;  // -1 if not available or invalid
    private final String unit;         // "b", "k", "m", "g", or empty
    private final boolean isAvailable;

    private static final long UNIT_K = 1024L;
    private static final long UNIT_M = 1024L * 1024;
    private static final long UNIT_G = 1024L * 1024 * 1024;
    private static final long UNIT_T = 1024L * 1024 * 1024 * 1024;

    private ParsedValue(String originalString, long numericValue, String unit, boolean isAvailable) {
        this.originalString = originalString;
        this.numericValue = numericValue;
        this.unit = unit;
        this.isAvailable = isAvailable;
    }

    /**
     * Parses a value string that may contain unit suffixes or markers.
     * Examples: "512m", "1.2g", "30000", "?", "64m+", "33m-"
     */
    public static ParsedValue parse(String valueStr) {
        if (valueStr == null || valueStr.trim().isEmpty() || "?".equals(valueStr.trim())) {
            return new ParsedValue(valueStr, -1, "", false);
        }

        String trimmed = valueStr.trim();

        if (trimmed.endsWith("+") || trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        // Extract unit suffix (trailing letters) and numeric prefix
        String numeric;
        String unit;
        int i = trimmed.length() - 1;
        while (i >= 0 && Character.isLetter(trimmed.charAt(i))) {
            i--;
        }
        unit = trimmed.substring(i + 1);
        numeric = trimmed.substring(0, i + 1);

        if (numeric.isEmpty()) {
            return new ParsedValue(valueStr, -1, "", false);
        }

        try {
            double doubleValue = Double.parseDouble(numeric);
            long bytesValue = (long) (doubleValue * getUnitMultiplier(unit));
            return new ParsedValue(valueStr, bytesValue, unit, true);
        } catch (NumberFormatException e) {
            return new ParsedValue(valueStr, -1, "", false);
        }
    }

    private static long getUnitMultiplier(String unit) {
        return switch (unit.toLowerCase()) {
            case "b" -> 1;
            case "k" -> UNIT_K;
            case "m" -> UNIT_M;
            case "g" -> UNIT_G;
            case "t" -> UNIT_T;
            default -> 1;
        };
    }

    /**
     * Get the numeric value in bytes (if available)
     */
    public long getBytes() {
        return numericValue;
    }

    /**
     * Get the numeric value in the original unit
     */
    public double getInOriginalUnit() {
        if (!isAvailable || numericValue == -1) {
            return -1;
        }
        long multiplier = getUnitMultiplier(unit);
        return (double) numericValue / multiplier;
    }

    /**
     * Convert to a different unit
     */
    public double getInUnit(String targetUnit) {
        if (!isAvailable || numericValue == -1) {
            return -1;
        }
        long multiplier = getUnitMultiplier(targetUnit);
        return (double) numericValue / multiplier;
    }

    /**
     * Get the original string representation
     */
    public String original() {
        return originalString;
    }

    /**
     * Whether this value was successfully parsed and is available
     */
    public boolean isAvailable() {
        return isAvailable;
    }

    /**
     * Get a human-readable representation with specified unit
     */
    public String format(String targetUnit) {
        if (!isAvailable) {
            return "?";
        }
        if (targetUnit == null || targetUnit.isEmpty()) {
            return originalString;
        }

        double value = getInUnit(targetUnit);
        if (value < 0) {
            return "?";
        }

        // Format with appropriate precision
        if (value >= 1000) {
            return String.format("%.1f%s", value, targetUnit);
        } else if (value >= 10) {
            return String.format("%.0f%s", value, targetUnit);
        } else {
            return String.format("%.1f%s", value, targetUnit);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ParsedValue other) {
            return Objects.equals(originalString, other.originalString) &&
                   numericValue == other.numericValue &&
                   Objects.equals(unit, other.unit);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalString, numericValue, unit);
    }

    @Override
    public String toString() {
        return originalString;
    }
}


