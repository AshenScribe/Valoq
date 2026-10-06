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
package protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("BufferUtil Tests")
class BufferUtilTest {

    private ByteBuf buffer;

    @BeforeEach
    void setUp() {
        buffer = Unpooled.buffer();
    }

    @AfterEach
    void tearDown() {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }

    @Nested
    @DisplayName("UUID Tests")
    class UUIDTests {

        @Test
        @DisplayName("writeUUID and readUUID - random UUID round trip")
        void testUUIDRoundTrip() {
            UUID original = UUID.randomUUID();
            BufferUtil.writeUUID(original, buffer);
            UUID read = BufferUtil.readUUID(buffer);
            assertEquals(original, read);
        }

        @Test
        @DisplayName("writeUUID writes exactly 16 bytes")
        void testUUIDWrites16Bytes() {
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            assertEquals(16, buffer.readableBytes());
        }

        @ParameterizedTest(name = "UUID: {0}")
        @MethodSource("provideUUIDs")
        @DisplayName("writeUUID/readUUID with various UUID values")
        void testUUIDVariousValues(UUID uuid) {
            BufferUtil.writeUUID(uuid, buffer);
            UUID read = BufferUtil.readUUID(buffer);
            assertEquals(uuid, read);
        }

        static Stream<UUID> provideUUIDs() {
            return Stream.of(
                    new UUID(0L, 0L),
                    new UUID(-1L, -1L),
                    new UUID(Long.MAX_VALUE, Long.MAX_VALUE),
                    new UUID(Long.MIN_VALUE, Long.MIN_VALUE),
                    new UUID(1L, 1L),
                    new UUID(0x123456789ABCDEF0L, 0x0FEDCBA987654321L),
                    new UUID(0xFFFFFFFF00000000L, 0x00000000FFFFFFFFL),
                    UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("test".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("writeUUID/readUUID preserves most and least significant bits")
        void testUUIDBitPreservation() {
            long msb = 0xDEADBEEFCAFEBABEL;
            long lsb = 0x0123456789ABCDEFL;
            UUID uuid = new UUID(msb, lsb);

            BufferUtil.writeUUID(uuid, buffer);

            assertEquals(msb, buffer.readLong());
            assertEquals(lsb, buffer.readLong());
        }

        @Test
        @DisplayName("readUUID reads from current reader index")
        void testUUIDReadFromCurrentIndex() {
            buffer.writeLong(0L); // Padding
            UUID expected = UUID.randomUUID();
            BufferUtil.writeUUID(expected, buffer);

            buffer.readLong(); // Consume padding
            UUID actual = BufferUtil.readUUID(buffer);

            assertEquals(expected, actual);
        }
    }

    @Nested
    @DisplayName("Short String Tests")
    class ShortStringTests {

        @Test
        @DisplayName("writeString/readString with null writes zero length and returns empty string")
        void testNullStringRoundTrip() {
            BufferUtil.writeString(null, buffer);
            assertEquals(2, buffer.readableBytes());
            assertEquals(0, buffer.readShort());

            buffer.resetReaderIndex();
            String result = BufferUtil.readString(buffer);
            assertEquals("", result);
        }

        @Test
        @DisplayName("writeString/readString with empty string")
        void testEmptyStringRoundTrip() {
            BufferUtil.writeString("", buffer);
            String result = BufferUtil.readString(buffer);
            assertEquals("", result);
        }

        @Test
        @DisplayName("writeString/readString with ASCII text")
        void testAsciiStringRoundTrip() {
            String original = "Hello, World!";
            BufferUtil.writeString(original, buffer);
            String result = BufferUtil.readString(buffer);
            assertEquals(original, result);
        }

        @ParameterizedTest(name = "string=\"{0}\"")
        @ValueSource(
                strings = {
                    "a",
                    "ab",
                    "Hello",
                    "Hello, World!",
                    "1234567890",
                    "!@#$%^&*()_+-=[]{}|;':\",./<>?",
                    "  leading and trailing spaces  ",
                    "\t\ttabs\t\t",
                    "MixedCASE123",
                    "test"
                })
        @DisplayName("writeString/readString with various short strings")
        void testVariousShortStrings(String str) {
            BufferUtil.writeString(str, buffer);
            String result = BufferUtil.readString(buffer);
            assertEquals(str, result);
        }

        @ParameterizedTest(name = "unicode: {0}")
        @ValueSource(
                strings = {
                    "héllo",
                    "日本語",
                    "Привет",
                    "مرحبا",
                    "こんにちは世界",
                    "안녕하세요",
                    "🎉🎊🎈",
                    "Ω≈ç√∫˜µ≤≥÷",
                    "ñáéíóú",
                    "Ĥéļľö"
                })
        @DisplayName("writeString/readString with unicode characters")
        void testUnicodeStrings(String str) {
            BufferUtil.writeString(str, buffer);
            String result = BufferUtil.readString(buffer);
            assertEquals(str, result);
        }

        @Test
        @DisplayName(
                "writeString with string exceeding 65535 bytes still writes via short truncation")
        void testVeryLongStringWithShortLength() {
            // Max length for short is 65535. A 65535-byte string should work.
            String str = "a".repeat(65535);
            BufferUtil.writeString(str, buffer);
            // length short = 65535, then 65535 bytes
            assertEquals(2 + 65535, buffer.readableBytes());

            String result = BufferUtil.readString(buffer);
            assertEquals(str, result);
        }

        @Test
        @DisplayName("writeString writes length prefix as short")
        void testLengthPrefixIsShort() {
            String str = "test"; // 4 bytes
            BufferUtil.writeString(str, buffer);
            assertEquals(2 + 4, buffer.readableBytes());
            assertEquals((short) 4, buffer.readShort());
        }

        @Test
        @DisplayName("readString with non-zero length reads exactly length bytes")
        void testReadStringExactLength() {
            String str = "hello";
            BufferUtil.writeString(str, buffer);
            buffer.writeByte(0xFF); // Trailing byte

            String result = BufferUtil.readString(buffer);
            assertEquals(str, result);
            assertEquals(1, buffer.readableBytes());
        }

        @Test
        @DisplayName("writeString advances writer index correctly")
        void testWriteStringAdvancesWriterIndex() {
            String str = "test";
            int initialWriterIndex = buffer.writerIndex();
            BufferUtil.writeString(str, buffer);
            assertEquals(initialWriterIndex + 2 + str.length(), buffer.writerIndex());
        }

        @Test
        @DisplayName("writeString emits UTF-8 encoded bytes")
        void testStringEncodedAsUtf8() {
            String str = "é"; // 2 bytes in UTF-8
            BufferUtil.writeString(str, buffer);
            assertEquals(2 + 2, buffer.readableBytes());
            short length = buffer.readShort();
            assertEquals(2, length);
            byte[] bytes = new byte[2];
            buffer.readBytes(bytes);
            assertArrayEquals(str.getBytes(StandardCharsets.UTF_8), bytes);
        }
    }

    @Nested
    @DisplayName("Long String Tests")
    class LongStringTests {

        @Test
        @DisplayName(
                "writeLongString/readLongString with null writes zero length and returns empty string")
        void testNullLongString() {
            BufferUtil.writeLongString(null, buffer);
            assertEquals(4, buffer.readableBytes());
            assertEquals(0, buffer.readInt());

            buffer.resetReaderIndex();
            String result = BufferUtil.readLongString(buffer);
            assertEquals("", result);
        }

        @Test
        @DisplayName("writeLongString/readLongString with empty string")
        void testEmptyLongString() {
            BufferUtil.writeLongString("", buffer);
            String result = BufferUtil.readLongString(buffer);
            assertEquals("", result);
        }

        @ParameterizedTest(name = "string=\"{0}\"")
        @ValueSource(
                strings = {
                    "a",
                    "Hello, World!",
                    "The quick brown fox jumps over the lazy dog",
                    "1234567890",
                    "!@#$%^&*()_+-=[]{}|;':\",./<>?"
                })
        @DisplayName("writeLongString/readLongString with various strings")
        void testVariousLongStrings(String str) {
            BufferUtil.writeLongString(str, buffer);
            String result = BufferUtil.readLongString(buffer);
            assertEquals(str, result);
        }

        @ParameterizedTest(name = "unicode: {0}")
        @ValueSource(
                strings = {"日本語のテキスト", "Привет мир", "مرحبا بالعالم", "🎉🎊🎈🎁", "Ĥéļľö Ŵōŕļď"})
        @DisplayName("writeLongString/readLongString with unicode characters")
        void testUnicodeLongStrings(String str) {
            BufferUtil.writeLongString(str, buffer);
            String result = BufferUtil.readLongString(buffer);
            assertEquals(str, result);
        }

        @Test
        @DisplayName("writeLongString writes length prefix as int")
        void testLengthPrefixIsInt() {
            String str = "test"; // 4 bytes
            BufferUtil.writeLongString(str, buffer);
            assertEquals(4 + 4, buffer.readableBytes());
            assertEquals(4, buffer.readInt());
        }

        @Test
        @DisplayName("writeLongString handles string larger than 65535 bytes")
        void testLongStringExceedingShortLimit() {
            String str = "a".repeat(100_000);
            BufferUtil.writeLongString(str, buffer);
            assertEquals(4 + 100_000, buffer.readableBytes());

            String result = BufferUtil.readLongString(buffer);
            assertEquals(str, result);
        }

        @Test
        @DisplayName("writeLongString/readLongString with very large string")
        void testVeryLargeString() {
            String str = "x".repeat(1_000_000);
            BufferUtil.writeLongString(str, buffer);
            String result = BufferUtil.readLongString(buffer);
            assertEquals(str, result);
        }

        @Test
        @DisplayName("readLongString with non-zero length reads exactly length bytes")
        void testReadLongStringExactLength() {
            String str = "hello";
            BufferUtil.writeLongString(str, buffer);
            buffer.writeByte(0xFF);

            String result = BufferUtil.readLongString(buffer);
            assertEquals(str, result);
            assertEquals(1, buffer.readableBytes());
        }

        @Test
        @DisplayName("writeLongString emits UTF-8 encoded bytes")
        void testLongStringEncodedAsUtf8() {
            String str = "é"; // 2 bytes in UTF-8
            BufferUtil.writeLongString(str, buffer);
            assertEquals(4 + 2, buffer.readableBytes());
            int length = buffer.readInt();
            assertEquals(2, length);
            byte[] bytes = new byte[2];
            buffer.readBytes(bytes);
            assertArrayEquals(str.getBytes(StandardCharsets.UTF_8), bytes);
        }
    }

    @Nested
    @DisplayName("Mixed Field Tests")
    class MixedFieldTests {

        @Test
        @DisplayName("Multiple fields written and read in order")
        void testMultipleFieldsRoundTrip() {
            UUID uuid = UUID.randomUUID();
            String shortStr = "short";
            String longStr = "this is a much longer string with more content";

            BufferUtil.writeUUID(uuid, buffer);
            BufferUtil.writeString(shortStr, buffer);
            BufferUtil.writeLongString(longStr, buffer);

            UUID readUuid = BufferUtil.readUUID(buffer);
            String readShortStr = BufferUtil.readString(buffer);
            String readLongStr = BufferUtil.readLongString(buffer);

            assertEquals(uuid, readUuid);
            assertEquals(shortStr, readShortStr);
            assertEquals(longStr, readLongStr);
            assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("Multiple null strings preserve order")
        void testMultipleNullStrings() {
            BufferUtil.writeString(null, buffer);
            BufferUtil.writeLongString(null, buffer);

            assertEquals("", BufferUtil.readString(buffer));
            assertEquals("", BufferUtil.readLongString(buffer));
            assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("Fields remain readable after interleaved writes and reads")
        void testInterleavedWriteRead() {
            UUID uuid1 = UUID.randomUUID();
            BufferUtil.writeUUID(uuid1, buffer);
            assertEquals(uuid1, BufferUtil.readUUID(buffer));

            String str = "hello";
            BufferUtil.writeString(str, buffer);
            assertEquals(str, BufferUtil.readString(buffer));

            String longStr = "world, this is longer";
            BufferUtil.writeLongString(longStr, buffer);
            assertEquals(longStr, BufferUtil.readLongString(buffer));

            assertEquals(0, buffer.readableBytes());
        }
    }

    @Nested
    @DisplayName("Class Construction Tests")
    class ConstructionTests {

        @Test
        @DisplayName("BufferUtil cannot be instantiated via reflection (private constructor)")
        void testPrivateConstructor() throws Exception {
            var constructor = BufferUtil.class.getDeclaredConstructor();
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        }

        @Test
        @DisplayName("BufferUtil is final")
        void testClassIsFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(BufferUtil.class.getModifiers()));
        }
    }
}
