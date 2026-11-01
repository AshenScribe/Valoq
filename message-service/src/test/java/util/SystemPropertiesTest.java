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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemPropertiesTest {

    private enum Profile {
        DEVELOPMENT,
        PRODUCTION
    }

    @BeforeEach
    void setUp() {
        clearAll();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        SystemProperties.PROFILE.clearValue();
        SystemProperties.PORT.clearValue();
        SystemProperties.DEBUG.clearValue();
    }

    @Test
    void shouldReturnDeclaredDefault() {
        Assertions.assertEquals("development", SystemProperties.PROFILE.getString());
    }

    @Test
    void shouldReturnExplicitlyConfiguredValue() {
        SystemProperties.PROFILE.setString("production");

        Assertions.assertEquals("production", SystemProperties.PROFILE.getString());
    }

    @Test
    void shouldThrowWhenRequiredPropertyIsMissing() {
        Assertions.assertThrows(
                IllegalStateException.class, () -> SystemProperties.PORT.getString());

        Assertions.assertThrows(IllegalStateException.class, () -> SystemProperties.PORT.getInt());
    }

    @Test
    void shouldConvertIntegerValues() {
        SystemProperties.PORT.setString("8080");
        Assertions.assertEquals(8080, SystemProperties.PORT.getInt());

        SystemProperties.PORT.setString("0x10");
        Assertions.assertEquals(16, SystemProperties.PORT.getInt());
    }

    @Test
    void shouldRejectMalformedInteger() {
        SystemProperties.PORT.setString("not-a-number");

        Assertions.assertThrows(
                IllegalArgumentException.class, () -> SystemProperties.PORT.getInt());
    }

    @Test
    void shouldConvertLongAndDouble() {
        SystemProperties.PORT.setString("123456789");
        Assertions.assertEquals(123456789L, SystemProperties.PORT.getLong());
        Assertions.assertEquals(123456789.0, SystemProperties.PORT.getDouble());
    }

    @Test
    void shouldParseBooleansStrictly() {
        SystemProperties.DEBUG.setString("TRUE");
        Assertions.assertTrue(SystemProperties.DEBUG.getBoolean());

        SystemProperties.DEBUG.setString("false");
        Assertions.assertFalse(SystemProperties.DEBUG.getBoolean());

        SystemProperties.DEBUG.setString("yes");

        Assertions.assertThrows(
                IllegalArgumentException.class, () -> SystemProperties.DEBUG.getBoolean());
    }

    @Test
    void shouldSupportOverrideDefaults() {
        Assertions.assertEquals(8080, SystemProperties.PORT.getInt(8080));

        Assertions.assertEquals("fallback", SystemProperties.PROFILE.getString("fallback"));
    }

    @Test
    void shouldSupportCustomConverters() {
        Assertions.assertEquals(
                "development".length(), SystemProperties.PROFILE.convert(String::length));
    }

    @Test
    void shouldConvertEnumValuesIgnoringCase() {
        SystemProperties.PROFILE.setString("production");

        Assertions.assertEquals(
                Profile.PRODUCTION, SystemProperties.PROFILE.getEnum(Profile.class));
    }

    @Test
    void shouldRejectUnknownEnumValues() {
        SystemProperties.PROFILE.setString("staging");

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SystemProperties.PROFILE.getEnum(Profile.class));
    }

    @Test
    void shouldSetAndReturnPreviousRawValue() {
        Assertions.assertNull(SystemProperties.PROFILE.setString("development"));

        Assertions.assertEquals("development", SystemProperties.PROFILE.setString("production"));

        Assertions.assertEquals("production", SystemProperties.PROFILE.getString());
    }

    @Test
    void shouldResetToDeclaredDefault() {
        SystemProperties.PROFILE.setString("production");
        SystemProperties.PROFILE.reset();

        Assertions.assertEquals("development", System.getProperty("profile"));
        Assertions.assertEquals("development", SystemProperties.PROFILE.getString());
    }

    @Test
    void shouldClearRequiredProperty() {
        SystemProperties.PORT.setInt(8080);
        SystemProperties.PORT.clearValue();

        Assertions.assertNull(System.getProperty("port"));

        Assertions.assertThrows(IllegalStateException.class, () -> SystemProperties.PORT.getInt());
    }
}
