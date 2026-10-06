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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EnvelopeDecoder Tests")
class EnvelopeDecoderTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private static EnvelopeDecoder newDecoder() {
        return new EnvelopeDecoder(1024 * 1024);
    }

    private static ByteBuf encodeEnvelope(
            byte magic, byte version, Opcode opcode, byte flags, int streamId, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(magic);
        buf.writeByte(version);
        buf.writeByte(opcode.getCode());
        buf.writeByte(flags);
        buf.writeInt(streamId);
        buf.writeInt(body.length);
        buf.writeBytes(body);
        return buf;
    }

    private static ByteBuf encodeEnvelope(Opcode opcode, int streamId, byte[] body) {
        return encodeEnvelope(Envelope.MAGIC, Envelope.VERSION, opcode, (byte) 0, streamId, body);
    }

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

        @Test
        @DisplayName("Decodes a well-formed frame with a body")
        void testDecodeWithBody() {
            channel = new EmbeddedChannel(newDecoder());
            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.SEND, 42, payload)));

            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(Envelope.MAGIC, env.getHeader().magic());
                assertEquals(Envelope.VERSION, env.getHeader().version());
                assertEquals(Opcode.SEND, env.getHeader().opcode());
                assertEquals(0, env.getHeader().flags());
                assertEquals(42, env.getHeader().streamId());
                assertEquals(payload.length, env.getHeader().bodyLength());
                assertArrayEquals(payload, byteBufToArray(env.getBody()));
            } finally {
                env.release();
            }
            assertNull(channel.readInbound());
        }

        @Test
        @DisplayName("Decodes a well-formed frame with an empty body")
        void testDecodeEmptyBody() {
            channel = new EmbeddedChannel(newDecoder());

            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.INIT, 1, new byte[0])));

            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(Opcode.INIT, env.getHeader().opcode());
                assertEquals(1, env.getHeader().streamId());
                assertEquals(0, env.getHeader().bodyLength());
                assertEquals(0, env.getBody().readableBytes());
            } finally {
                env.release();
            }
        }

        @ParameterizedTest(name = "opcode={0}")
        @EnumSource(Opcode.class)
        @DisplayName("Decodes every known opcode")
        void testDecodeAllOpcodes(Opcode opcode) {
            channel = new EmbeddedChannel(newDecoder());

            assertTrue(channel.writeInbound(encodeEnvelope(opcode, 7, new byte[] {1, 2, 3})));

            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(opcode, env.getHeader().opcode());
            } finally {
                env.release();
            }
        }

        @ParameterizedTest(name = "streamId={0}")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 42, Integer.MAX_VALUE})
        @DisplayName("Decodes various stream IDs (including -1 for EVENT_STREAM_ID)")
        void testDecodeStreamIds(int streamId) {
            channel = new EmbeddedChannel(newDecoder());

            assertTrue(
                    channel.writeInbound(encodeEnvelope(Opcode.EVENT, streamId, new byte[] {99})));

            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(streamId, env.getHeader().streamId());
            } finally {
                env.release();
            }
        }

        @ParameterizedTest(name = "flags={0}")
        @ValueSource(bytes = {0, 1, 2, 0x7F, (byte) 0x80, (byte) 0xFF})
        @DisplayName("Decodes arbitrary flag values")
        void testDecodeFlags(byte flags) {
            channel = new EmbeddedChannel(newDecoder());

            ByteBuf frame =
                    encodeEnvelope(
                            Envelope.MAGIC, Envelope.VERSION, Opcode.ACK, flags, 1, new byte[0]);
            assertTrue(channel.writeInbound(frame));

            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(flags, env.getHeader().flags());
            } finally {
                env.release();
            }
        }
    }

    @Nested
    @DisplayName("Frame Splitting / LengthFieldBasedFrameDecoder Behavior")
    class FrameSplitting {

        @Test
        @DisplayName("Returns null when the header is incomplete")
        void testIncompleteHeader() {
            channel = new EmbeddedChannel(newDecoder());
            ByteBuf partial = Unpooled.buffer();
            partial.writeByte(Envelope.MAGIC);
            partial.writeByte(Envelope.VERSION);
            partial.writeByte(Opcode.SEND.getCode());
            partial.writeByte((byte) 0);
            partial.writeShort(10);

            assertFalse(channel.writeInbound(partial));
            assertNull(channel.readInbound());
        }

        @Test
        @DisplayName("Returns null when the body is incomplete")
        void testIncompleteBody() {
            channel = new EmbeddedChannel(newDecoder());
            ByteBuf partial = Unpooled.buffer();
            partial.writeByte(Envelope.MAGIC);
            partial.writeByte(Envelope.VERSION);
            partial.writeByte(Opcode.SEND.getCode());
            partial.writeByte((byte) 0);
            partial.writeInt(1);
            partial.writeInt(100);
            partial.writeBytes(new byte[10]);

            assertFalse(channel.writeInbound(partial));
            assertNull(channel.readInbound());
        }

        @Test
        @DisplayName("Two frames in a single buffer are both decoded")
        void testTwoFramesInOneBuffer() {
            channel = new EmbeddedChannel(newDecoder());

            ByteBuf combined = Unpooled.buffer();
            combined.writeBytes(encodeEnvelope(Opcode.SEND, 1, new byte[] {1}));
            combined.writeBytes(encodeEnvelope(Opcode.EVENT, 2, new byte[] {2, 3}));

            assertTrue(channel.writeInbound(combined));

            Envelope first = channel.readInbound();
            Envelope second = channel.readInbound();
            assertNotNull(first);
            assertNotNull(second);
            try {
                assertEquals(Opcode.SEND, first.getHeader().opcode());
                assertEquals(1, first.getHeader().streamId());
                assertEquals(Opcode.EVENT, second.getHeader().opcode());
                assertEquals(2, second.getHeader().streamId());
                assertArrayEquals(new byte[] {2, 3}, byteBufToArray(second.getBody()));
            } finally {
                first.release();
                second.release();
            }
            assertNull(channel.readInbound());
        }

        @Test
        @DisplayName("Frame arriving in multiple writes is decoded once complete")
        void testFrameSplitAcrossWrites() {
            channel = new EmbeddedChannel(newDecoder());

            ByteBuf frame =
                    encodeEnvelope(Opcode.READY, 5, "payload".getBytes(StandardCharsets.UTF_8));
            int total = frame.readableBytes();
            int split = 7;

            byte[] all = new byte[total];
            frame.getBytes(frame.readerIndex(), all);
            frame.release();

            ByteBuf firstChunk = Unpooled.wrappedBuffer(all, 0, split);
            ByteBuf secondChunk = Unpooled.wrappedBuffer(all, split, total - split);

            assertFalse(channel.writeInbound(firstChunk));
            assertNull(channel.readInbound());

            assertTrue(channel.writeInbound(secondChunk));
            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(Opcode.READY, env.getHeader().opcode());
                assertEquals(5, env.getHeader().streamId());
                assertEquals("payload", byteBufToString(env.getBody()));
            } finally {
                env.release();
            }
        }

        @Test
        @DisplayName("Empty body frame is decoded from a single write")
        void testEmptyBodyFrame() {
            channel = new EmbeddedChannel(newDecoder());

            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.SYNC, 1, new byte[0])));
            Envelope env = channel.readInbound();
            assertNotNull(env);
            try {
                assertEquals(0, env.getHeader().bodyLength());
            } finally {
                env.release();
            }
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Rejects invalid magic byte")
        void testInvalidMagic() {
            channel = new EmbeddedChannel(newDecoder());

            ByteBuf frame =
                    encodeEnvelope(
                            (byte) 0x42, Envelope.VERSION, Opcode.SEND, (byte) 0, 1, new byte[0]);

            assertDecoderRejects(
                    channel, frame, IllegalArgumentException.class, "Invalid magic byte");
        }

        @Test
        @DisplayName("Rejects unknown opcode")
        void testInvalidOpcode() {
            channel = new EmbeddedChannel(newDecoder());
            ByteBuf frame =
                    encodeEnvelope(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            (byte) 0x7F,
                            (byte) 0,
                            1,
                            new byte[0]);

            assertDecoderRejects(channel, frame, IllegalArgumentException.class, "Unknown opcode");
        }

        @Test
        @DisplayName("Rejects unknown opcode within LOOKUP range but null")
        void testOpcodeInRangeButNull() {
            channel = new EmbeddedChannel(newDecoder());
            ByteBuf frame =
                    encodeEnvelope(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            (byte) 0x09,
                            (byte) 0,
                            1,
                            new byte[0]);

            assertDecoderRejects(channel, frame, IllegalArgumentException.class, "Unknown opcode");
        }

        @Test
        @DisplayName("Rejects negative byte for opcode")
        void testNegativeOpcode() {
            channel = new EmbeddedChannel(newDecoder());

            ByteBuf frame =
                    encodeEnvelope(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            (byte) 0x80,
                            (byte) 0,
                            1,
                            new byte[0]);

            assertDecoderRejects(channel, frame, IllegalArgumentException.class, "Unknown opcode");
        }

        @Test
        @DisplayName("Frame exceeding maxFrameLength is rejected")
        void testExceedsMaxFrameLength() {
            EmbeddedChannel smallChannel = new EmbeddedChannel(new EnvelopeDecoder(20));
            try {
                ByteBuf frame = encodeEnvelope(Opcode.SEND, 1, new byte[100]);
                assertThrows(Exception.class, () -> smallChannel.writeInbound(frame));
            } finally {
                smallChannel.finishAndReleaseAll();
            }
        }
    }

    @Nested
    @DisplayName("Reference Counting / Lifecycle")
    class ReferenceCounting {

        @Test
        @DisplayName("Body has its own refcount independent of the original frame")
        void testBodyRefCount() {
            channel = new EmbeddedChannel(newDecoder());

            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.SEND, 1, new byte[] {1, 2, 3})));

            Envelope env = channel.readInbound();
            assertNotNull(env);
            assertEquals(1, env.getBody().refCnt());
            env.release();
            assertEquals(0, env.getBody().refCnt());
        }

        @Test
        @DisplayName("Releasing twice does not throw (guarded by refCnt check)")
        void testDoubleReleaseIsSafe() {
            channel = new EmbeddedChannel(newDecoder());

            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.SEND, 1, new byte[] {1})));
            Envelope env = channel.readInbound();
            assertNotNull(env);

            env.release();
            assertDoesNotThrow(env::release);
        }

        @Test
        @DisplayName("Envelope.retain increments body refcount")
        void testEnvelopeRetain() {
            channel = new EmbeddedChannel(newDecoder());
            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.SEND, 1, new byte[] {1})));
            Envelope env = channel.readInbound();
            assertNotNull(env);

            try {
                Envelope same = env.retain();
                assertSame(env, same);
                assertEquals(2, env.getBody().refCnt());

                env.release();
                assertEquals(1, env.getBody().refCnt());
                env.release();
                assertEquals(0, env.getBody().refCnt());
            } finally {
                if (env.getBody().refCnt() > 0) {
                    env.release();
                }
            }
        }

        @Test
        @DisplayName("Envelope.duplicate shares content with new refcount")
        void testEnvelopeDuplicate() {
            channel = new EmbeddedChannel(newDecoder());
            assertTrue(channel.writeInbound(encodeEnvelope(Opcode.SEND, 1, new byte[] {7, 8, 9})));
            Envelope env = channel.readInbound();
            assertNotNull(env);

            Envelope dup = env.duplicate();
            try {
                assertNotSame(env, dup);
                assertEquals(env.getHeader(), dup.getHeader());
                assertArrayEquals(byteBufToArray(env.getBody()), byteBufToArray(dup.getBody()));
                assertTrue(env.getBody().refCnt() >= 2);
            } finally {
                env.release();
                dup.release();
            }
        }
    }

    @Nested
    @DisplayName("Envelope Factory / Accessors")
    class EnvelopeFactory {

        @Test
        @DisplayName("Envelope.create builds a header from body readableBytes")
        void testCreate() {
            ByteBuf body = Unpooled.buffer().writeBytes(new byte[] {1, 2, 3, 4});
            Envelope env = Envelope.create(Opcode.SEND, 99, body);
            try {
                assertEquals(Envelope.MAGIC, env.getHeader().magic());
                assertEquals(Envelope.VERSION, env.getHeader().version());
                assertEquals(Opcode.SEND, env.getHeader().opcode());
                assertEquals(0, env.getHeader().flags());
                assertEquals(99, env.getHeader().streamId());
                assertEquals(4, env.getHeader().bodyLength());
                assertSame(body, env.getBody());
            } finally {
                env.release();
            }
        }

        @Test
        @DisplayName("Envelope.createEmpty yields zero-length body")
        void testCreateEmpty() {
            Envelope env = Envelope.createEmpty(Opcode.SYNC, 5);
            try {
                assertEquals(0, env.getHeader().bodyLength());
                assertEquals(0, env.getBody().readableBytes());
            } finally {
                env.release();
            }
        }

        @Test
        @DisplayName("Envelope constructor rejects null header")
        void testNullHeaderRejected() {
            assertThrows(
                    NullPointerException.class, () -> new Envelope(null, Unpooled.EMPTY_BUFFER));
        }

        @Test
        @DisplayName("Envelope constructor rejects null body")
        void testNullBodyRejected() {
            Envelope.Header header =
                    new Envelope.Header(
                            Envelope.MAGIC, Envelope.VERSION, Opcode.SEND, (byte) 0, 1, 0);
            assertThrows(NullPointerException.class, () -> new Envelope(header, null));
        }

        @Test
        @DisplayName("EVENT_STREAM_ID constant is -1")
        void testEventStreamId() {
            assertEquals(-1, Envelope.EVENT_STREAM_ID);
        }
    }

    @Nested
    @DisplayName("Opcode Lookup")
    class OpcodeLookup {

        @ParameterizedTest(name = "{0} -> 0x{1}")
        @MethodSource("opcodeRoundTrips")
        @DisplayName("Opcode.fromByte round-trips with getCode")
        void testRoundTrip(Opcode opcode, byte expectedCode) {
            assertEquals(opcode, Opcode.fromByte(opcode.getCode()));
            assertEquals(expectedCode, opcode.getCode());
        }

        static Stream<Arguments> opcodeRoundTrips() {
            return Stream.of(
                    Arguments.of(Opcode.ERROR, (byte) 0x00),
                    Arguments.of(Opcode.INIT, (byte) 0x01),
                    Arguments.of(Opcode.READY, (byte) 0x02),
                    Arguments.of(Opcode.SEND, (byte) 0x03),
                    Arguments.of(Opcode.EVENT, (byte) 0x04),
                    Arguments.of(Opcode.SYNC, (byte) 0x05),
                    Arguments.of(Opcode.ACK, (byte) 0x06),
                    Arguments.of(Opcode.ACK_DELIVERED, (byte) 0x07),
                    Arguments.of(Opcode.ACK_READ, (byte) 0x08));
        }

        @ParameterizedTest(name = "unknown opcode 0x{0}")
        @ValueSource(
                bytes = {
                    (byte) 0x09,
                    (byte) 0x0A,
                    (byte) 0x10,
                    (byte) 0x7F,
                    (byte) 0x80,
                    (byte) 0xFF
                })
        @DisplayName("Opcode.fromByte rejects unknown / out-of-range values")
        void testUnknownOpcodeRejected(byte raw) {
            assertThrows(IllegalArgumentException.class, () -> Opcode.fromByte(raw));
        }
    }

    private static byte[] byteBufToArray(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    private static String byteBufToString(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return new String(out, StandardCharsets.UTF_8);
    }

    private static ByteBuf encodeEnvelope(
            byte magic, byte version, byte opcode, byte flags, int streamId, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(magic);
        buf.writeByte(version);
        buf.writeByte(opcode);
        buf.writeByte(flags);
        buf.writeInt(streamId);
        buf.writeInt(body.length);
        buf.writeBytes(body);
        return buf;
    }

    private static void assertDecoderRejects(
            EmbeddedChannel ch,
            ByteBuf frame,
            Class<? extends Throwable> causeType,
            String messageFragment) {
        DecoderException ex = assertThrows(DecoderException.class, () -> ch.writeInbound(frame));

        Throwable cause = ex.getCause();
        assertNotNull(cause, "DecoderException had no cause");
        assertTrue(
                causeType.isInstance(cause),
                () ->
                        "expected cause of type "
                                + causeType.getName()
                                + " but was "
                                + cause.getClass().getName());
        assertTrue(
                cause.getMessage() != null && cause.getMessage().contains(messageFragment),
                () -> "cause message was: " + cause.getMessage());
    }
}
