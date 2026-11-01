/*
 * MIT License
 *
 * Copyright (c) 2026 Valoq
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package util;

import java.util.Locale;
import java.util.Objects;

/**
 * Provides typed access to JVM system properties.
 *
 * <p>Property values are read when a getter is called. This class does not cache values, validate
 * application-wide configuration at startup, or provide per-thread configuration.
 *
 * <p>System properties are shared across the JVM. Mutating them can affect unrelated components and
 * must be coordinated with concurrent code.
 */
public enum SystemProperties {
    PROFILE("profile", "development"),
    PORT("port"),
    DEBUG("debug", "false");

    private static final PropertyConverter<String> STRING_CONVERTER = value -> value;

    private static final PropertyConverter<Boolean> BOOLEAN_CONVERTER =
            value -> {
                if ("true".equalsIgnoreCase(value)) {
                    return true;
                }

                if ("false".equalsIgnoreCase(value)) {
                    return false;
                }

                throw new IllegalArgumentException(
                        "expected boolean value (true or false) but got '" + value + "'");
            };

    private static final PropertyConverter<Integer> INTEGER_CONVERTER =
            value -> {
                try {
                    return Integer.decode(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "expected integer value but got '" + value + "'", e);
                }
            };

    private static final PropertyConverter<Long> LONG_CONVERTER =
            value -> {
                try {
                    return Long.decode(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "expected long value but got '" + value + "'", e);
                }
            };

    private static final PropertyConverter<Double> DOUBLE_CONVERTER =
            value -> {
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "expected floating-point value but got '" + value + "'", e);
                }
            };

    private final String key;
    private final String defaultValue;

    SystemProperties(String key, String defaultValue) {
        this.key = Objects.requireNonNull(key, "key");
        this.defaultValue = defaultValue;
    }

    SystemProperties(String key) {
        this(key, null);
    }

    public String getKey() {
        return key;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * Gets the system property, falling back to its declared default.
     *
     * @throws IllegalStateException if neither a system property nor a declared default exists
     */
    private String getValue() {
        String value = System.getProperty(key);

        if (value != null) {
            return value;
        }

        if (defaultValue != null) {
            return defaultValue;
        }

        throw new IllegalStateException("Missing system property and default value: " + key);
    }

    /** Converts the property value using the supplied converter. */
    public <T> T convert(PropertyConverter<T> converter) {
        Objects.requireNonNull(converter, "converter");

        String value = getValue();

        try {
            return converter.convert(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value for system property '" + key + "': " + e.getMessage(), e);
        }
    }

    /** Gets the property as a String. */
    public String getString() {
        return convert(STRING_CONVERTER);
    }

    /**
     * Gets the system property, or the supplied fallback if the property has not explicitly been
     * set. The declared default is not consulted.
     */
    public String getString(String overrideDefaultValue) {
        String value = System.getProperty(key);
        return value == null ? overrideDefaultValue : value;
    }

    /** Gets the property as a boolean. */
    public boolean getBoolean() {
        return convert(BOOLEAN_CONVERTER);
    }

    /**
     * Gets the explicitly configured property as a boolean, or returns the supplied fallback if the
     * system property is absent.
     */
    public boolean getBoolean(boolean overrideDefaultValue) {
        String value = System.getProperty(key);

        return value == null
                ? overrideDefaultValue
                : convertExplicitValue(value, BOOLEAN_CONVERTER);
    }

    /** Gets the property as an int. */
    public int getInt() {
        return convert(INTEGER_CONVERTER);
    }

    public int getInt(int overrideDefaultValue) {
        String value = System.getProperty(key);

        return value == null
                ? overrideDefaultValue
                : convertExplicitValue(value, INTEGER_CONVERTER);
    }

    /** Gets the property as a long. */
    public long getLong() {
        return convert(LONG_CONVERTER);
    }

    public long getLong(long overrideDefaultValue) {
        String value = System.getProperty(key);

        return value == null ? overrideDefaultValue : convertExplicitValue(value, LONG_CONVERTER);
    }

    /** Gets the property as a double. */
    public double getDouble() {
        return convert(DOUBLE_CONVERTER);
    }

    public double getDouble(double overrideDefaultValue) {
        String value = System.getProperty(key);

        return value == null ? overrideDefaultValue : convertExplicitValue(value, DOUBLE_CONVERTER);
    }

    /** Gets the property as an enum constant, ignoring case. */
    public <T extends Enum<T>> T getEnum(Class<T> enumType) {
        Objects.requireNonNull(enumType, "enumType");

        return convert(
                value -> {
                    try {
                        return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "expected one of "
                                        + java.util.Arrays.toString(enumType.getEnumConstants())
                                        + " but got '"
                                        + value
                                        + "'",
                                e);
                    }
                });
    }

    /** Converts an explicitly configured value without consulting defaults. */
    private <T> T convertExplicitValue(String value, PropertyConverter<T> converter) {
        try {
            return converter.convert(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value for system property '" + key + "': " + e.getMessage(), e);
        }
    }

    /**
     * Sets the property to a string value.
     *
     * @return the previous raw value, or null if absent
     */
    public String setString(String value) {
        return System.setProperty(key, Objects.requireNonNull(value, "value"));
    }

    public String setBoolean(boolean value) {
        return System.setProperty(key, Boolean.toString(value));
    }

    public String setInt(int value) {
        return System.setProperty(key, Integer.toString(value));
    }

    public String setLong(long value) {
        return System.setProperty(key, Long.toString(value));
    }

    public String setDouble(double value) {
        return System.setProperty(key, Double.toString(value));
    }

    /**
     * Removes the explicitly configured system property. Subsequent getters may still return the
     * declared default.
     */
    public void clearValue() {
        System.clearProperty(key);
    }

    /**
     * Restores the declared default. If no default exists, removes the explicitly configured
     * property.
     */
    public void reset() {
        if (defaultValue != null) {
            System.setProperty(key, defaultValue);
        } else {
            clearValue();
        }
    }

    @FunctionalInterface
    public interface PropertyConverter<T> {
        T convert(String value);
    }
}
