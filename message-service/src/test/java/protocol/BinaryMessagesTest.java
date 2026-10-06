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

import com.datastax.oss.driver.api.core.uuid.Uuids;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import server.model.Event;
import server.model.EventType;

@DisplayName("BinaryMessages Tests")
class BinaryMessagesTest {

    private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

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

    private static byte[] readAllBytes(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    private static void releaseEnvelope(Envelope env) {
        if (env != null) {
            env.release();
        }
    }

    @Nested
    @DisplayName("ReceiptMode")
    class ReceiptModeTests {

        @Test
        @DisplayName("WATERMARK code is 0x00")
        void testWatermarkCode() {
            Assertions.assertEquals((byte) 0x00, BinaryMessages.ReceiptMode.WATERMARK.getCode());
        }

        @Test
        @DisplayName("EXPLICIT_LIST code is 0x01")
        void testExplicitListCode() {
            Assertions.assertEquals(
                    (byte) 0x01, BinaryMessages.ReceiptMode.EXPLICIT_LIST.getCode());
        }

        @Test
        @DisplayName("fromByte(0x00) returns WATERMARK")
        void testFromByteWatermark() {
            Assertions.assertEquals(
                    BinaryMessages.ReceiptMode.WATERMARK,
                    BinaryMessages.ReceiptMode.fromByte((byte) 0x00));
        }

        @Test
        @DisplayName("fromByte(0x01) returns EXPLICIT_LIST")
        void testFromByteExplicitList() {
            Assertions.assertEquals(
                    BinaryMessages.ReceiptMode.EXPLICIT_LIST,
                    BinaryMessages.ReceiptMode.fromByte((byte) 0x01));
        }

        @ParameterizedTest(name = "non-zero byte 0x{0} -> EXPLICIT_LIST")
        @ValueSource(bytes = {0x02, 0x7F, (byte) 0x80, (byte) 0xFF})
        @DisplayName("fromByte maps any non-zero byte to EXPLICIT_LIST")
        void testFromByteNonZeroMapsToExplicitList(byte raw) {
            Assertions.assertEquals(
                    BinaryMessages.ReceiptMode.EXPLICIT_LIST,
                    BinaryMessages.ReceiptMode.fromByte(raw));
        }
    }

    @Nested
    @DisplayName("InitRequest")
    class InitRequestTests {

        @Test
        @DisplayName("decode reads a long string token")
        void testDecode() {
            BufferUtil.writeLongString("token-abc", buffer);

            BinaryMessages.InitRequest req = BinaryMessages.InitRequest.decode(buffer);

            Assertions.assertEquals("token-abc", req.token());
            Assertions.assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("decode of null token yields empty string")
        void testDecodeNullToken() {
            BufferUtil.writeLongString(null, buffer);
            BinaryMessages.InitRequest req = BinaryMessages.InitRequest.decode(buffer);
            Assertions.assertEquals("", req.token());
        }

        @ParameterizedTest(name = "token=\"{0}\"")
        @ValueSource(strings = {"", "a", "short", "longer-token-value-123", "日本語トークン"})
        @DisplayName("decode handles various token strings")
        void testDecodeVarious(String token) {
            BufferUtil.writeLongString(token, buffer);
            Assertions.assertEquals(token, BinaryMessages.InitRequest.decode(buffer).token());
        }
    }

    @Nested
    @DisplayName("SendRequest")
    class SendRequestTests {

        @Test
        @DisplayName("encode/decode round trip preserves all fields")
        void testRoundTrip() {
            UUID convId = UUID.randomUUID();
            Instant ts = Instant.ofEpochMilli(1_700_000_000_123L);
            BinaryMessages.SendRequest original =
                    new BinaryMessages.SendRequest(convId, ts, "hello");

            BinaryMessages.SendRequest.encode(original, buffer);
            BinaryMessages.SendRequest decoded = BinaryMessages.SendRequest.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("encode writes exactly uuid + long + longString")
        void testWireFormat() {
            UUID convId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Instant ts = Instant.ofEpochMilli(42L);
            BinaryMessages.SendRequest req = new BinaryMessages.SendRequest(convId, ts, "hi");

            BinaryMessages.SendRequest.encode(req, buffer);

            Assertions.assertEquals(16 + 8 + 4 + 2, buffer.readableBytes());
            Assertions.assertEquals(convId.getMostSignificantBits(), buffer.readLong());
            Assertions.assertEquals(convId.getLeastSignificantBits(), buffer.readLong());
            Assertions.assertEquals(42L, buffer.readLong());
            Assertions.assertEquals(2, buffer.readInt());
        }

        @ParameterizedTest(name = "epochMilli={0}")
        @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1L, 1_700_000_000_000L, Long.MAX_VALUE})
        @DisplayName("round trip preserves extreme timestamps")
        void testExtremeTimestamps(long epochMilli) {
            BinaryMessages.SendRequest req =
                    new BinaryMessages.SendRequest(
                            UUID.randomUUID(), Instant.ofEpochMilli(epochMilli), "p");

            BinaryMessages.SendRequest.encode(req, buffer);
            BinaryMessages.SendRequest decoded = BinaryMessages.SendRequest.decode(buffer);

            Assertions.assertEquals(epochMilli, decoded.timestamp().toEpochMilli());
        }

        @ParameterizedTest(name = "payload=\"{0}\"")
        @ValueSource(strings = {"", "hi", "a longer payload with spaces", "🎉 unicode 🎊"})
        @DisplayName("round trip preserves payload")
        void testVariousPayloads(String payload) {
            BinaryMessages.SendRequest req =
                    new BinaryMessages.SendRequest(
                            UUID.randomUUID(), Instant.ofEpochMilli(1L), payload);

            BinaryMessages.SendRequest.encode(req, buffer);
            Assertions.assertEquals(payload, BinaryMessages.SendRequest.decode(buffer).payload());
        }

        @Test
        @DisplayName("encode of null payload writes empty long string")
        void testNullPayload() {
            BinaryMessages.SendRequest req =
                    new BinaryMessages.SendRequest(
                            UUID.randomUUID(), Instant.ofEpochMilli(1L), null);

            BinaryMessages.SendRequest.encode(req, buffer);
            Assertions.assertEquals("", BinaryMessages.SendRequest.decode(buffer).payload());
        }

        @Test
        @DisplayName("round trip is stable across many random inputs")
        void testManyRandomRoundTrips() {
            for (int i = 0; i < 100; i++) {
                ByteBuf local = Unpooled.buffer();
                try {
                    BinaryMessages.SendRequest req =
                            new BinaryMessages.SendRequest(
                                    UUID.randomUUID(),
                                    Instant.ofEpochMilli((long) (Math.random() * 1e12)),
                                    "payload-" + i);
                    BinaryMessages.SendRequest.encode(req, local);
                    Assertions.assertEquals(req, BinaryMessages.SendRequest.decode(local));
                } finally {
                    local.release();
                }
            }
        }
    }

    @Nested
    @DisplayName("SyncRequest")
    class SyncRequestTests {

        @Test
        @DisplayName("decode reads a UUID cursor")
        void testDecode() {
            UUID cursor = UUID.randomUUID();
            BufferUtil.writeUUID(cursor, buffer);

            BinaryMessages.SyncRequest req = BinaryMessages.SyncRequest.decode(buffer);

            Assertions.assertEquals(cursor, req.cursorEventId());
            Assertions.assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("decode reads extreme UUID values")
        void testExtremeUuid() {
            UUID cursor = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
            BufferUtil.writeUUID(cursor, buffer);
            Assertions.assertEquals(
                    cursor, BinaryMessages.SyncRequest.decode(buffer).cursorEventId());
        }
    }

    @Nested
    @DisplayName("ReceiptRequest")
    class ReceiptRequestTests {

        @Test
        @DisplayName("watermark factory produces WATERMARK mode with empty list")
        void testWatermarkFactory() {
            UUID convId = UUID.randomUUID();
            UUID watermarkId = UUID.randomUUID();

            BinaryMessages.ReceiptRequest req =
                    BinaryMessages.ReceiptRequest.watermark(convId, watermarkId);

            Assertions.assertEquals(convId, req.conversationId());
            Assertions.assertEquals(BinaryMessages.ReceiptMode.WATERMARK, req.mode());
            Assertions.assertEquals(watermarkId, req.watermarkEventId());
            Assertions.assertTrue(req.explicitEventIds().isEmpty());
        }

        @Test
        @DisplayName("explicit factory produces EXPLICIT_LIST mode with null watermark")
        void testExplicitFactory() {
            UUID convId = UUID.randomUUID();
            List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

            BinaryMessages.ReceiptRequest req = BinaryMessages.ReceiptRequest.explicit(convId, ids);

            Assertions.assertEquals(convId, req.conversationId());
            Assertions.assertEquals(BinaryMessages.ReceiptMode.EXPLICIT_LIST, req.mode());
            Assertions.assertNull(req.watermarkEventId());
            Assertions.assertEquals(ids, req.explicitEventIds());
        }

        @Test
        @DisplayName("watermark round trip")
        void testWatermarkRoundTrip() {
            UUID convId = UUID.randomUUID();
            UUID watermarkId = UUID.randomUUID();
            BinaryMessages.ReceiptRequest original =
                    BinaryMessages.ReceiptRequest.watermark(convId, watermarkId);

            BinaryMessages.ReceiptRequest.encode(original, buffer);
            BinaryMessages.ReceiptRequest decoded = BinaryMessages.ReceiptRequest.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("explicit round trip with multiple ids")
        void testExplicitRoundTrip() {
            UUID convId = UUID.randomUUID();
            List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            BinaryMessages.ReceiptRequest original =
                    BinaryMessages.ReceiptRequest.explicit(convId, ids);

            BinaryMessages.ReceiptRequest.encode(original, buffer);
            BinaryMessages.ReceiptRequest decoded = BinaryMessages.ReceiptRequest.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("explicit round trip with empty list")
        void testExplicitEmptyList() {
            BinaryMessages.ReceiptRequest original =
                    BinaryMessages.ReceiptRequest.explicit(
                            UUID.randomUUID(), Collections.emptyList());

            BinaryMessages.ReceiptRequest.encode(original, buffer);
            BinaryMessages.ReceiptRequest decoded = BinaryMessages.ReceiptRequest.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertEquals(0, buffer.readableBytes());
        }

        @Test
        @DisplayName("wire format for watermark: uuid + mode + uuid")
        void testWatermarkWireFormat() {
            UUID convId = UUID.randomUUID();
            UUID watermarkId = UUID.randomUUID();
            BinaryMessages.ReceiptRequest.encode(
                    BinaryMessages.ReceiptRequest.watermark(convId, watermarkId), buffer);

            Assertions.assertEquals(16 + 1 + 16, buffer.readableBytes());
        }

        @Test
        @DisplayName("wire format for explicit list: uuid + mode + short count + 16*N")
        void testExplicitWireFormat() {
            List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
            BinaryMessages.ReceiptRequest.encode(
                    BinaryMessages.ReceiptRequest.explicit(UUID.randomUUID(), ids), buffer);

            Assertions.assertEquals(16 + 1 + 2 + 16 * ids.size(), buffer.readableBytes());
        }

        @Test
        @DisplayName("explicit list count is limited to unsigned short (65535 max)")
        void testExplicitListMaxSize() {
            List<UUID> ids = new ArrayList<>(65535);
            for (int i = 0; i < 65535; i++) {
                ids.add(UUID.randomUUID());
            }
            BinaryMessages.ReceiptRequest original =
                    BinaryMessages.ReceiptRequest.explicit(UUID.randomUUID(), ids);

            BinaryMessages.ReceiptRequest.encode(original, buffer);
            BinaryMessages.ReceiptRequest decoded = BinaryMessages.ReceiptRequest.decode(buffer);

            Assertions.assertEquals(65535, decoded.explicitEventIds().size());
            Assertions.assertEquals(original, decoded);
        }

        @ParameterizedTest(name = "count={0}")
        @ValueSource(ints = {0, 1, 2, 5, 10})
        @DisplayName("explicit round trip across list sizes")
        void testExplicitVariousSizes(int count) {
            List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ids.add(UUID.randomUUID());
            }
            BinaryMessages.ReceiptRequest original =
                    BinaryMessages.ReceiptRequest.explicit(UUID.randomUUID(), ids);

            BinaryMessages.ReceiptRequest.encode(original, buffer);
            Assertions.assertEquals(original, BinaryMessages.ReceiptRequest.decode(buffer));
        }
    }

    @Nested
    @DisplayName("Envelope Factories")
    class EnvelopeFactories {

        @Test
        @DisplayName("createReadyResponse uses READY opcode and empty body")
        void testReadyResponse() {
            Envelope env = BinaryMessages.createReadyResponse(7);
            try {
                Assertions.assertEquals(Opcode.READY, env.getHeader().opcode());
                Assertions.assertEquals(7, env.getHeader().streamId());
                Assertions.assertEquals(0, env.getHeader().bodyLength());
                Assertions.assertEquals(0, env.getBody().readableBytes());
            } finally {
                releaseEnvelope(env);
            }
        }

        @ParameterizedTest(name = "streamId={0}")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 42, Integer.MAX_VALUE})
        @DisplayName("createReadyResponse preserves streamId")
        void testReadyResponseStreamIds(int streamId) {
            Envelope env = BinaryMessages.createReadyResponse(streamId);
            try {
                Assertions.assertEquals(streamId, env.getHeader().streamId());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("createAckResponse writes 16-byte UUID body")
        void testAckResponse() {
            UUID eventId = UUID.randomUUID();
            Envelope env = BinaryMessages.createAckResponse(3, eventId, ALLOC);
            try {
                Assertions.assertEquals(Opcode.ACK, env.getHeader().opcode());
                Assertions.assertEquals(3, env.getHeader().streamId());
                Assertions.assertEquals(16, env.getHeader().bodyLength());
                Assertions.assertEquals(eventId, BinaryMessages.decodeAckResponse(env.getBody()));
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("createErrorResponse writes short-string error message")
        void testErrorResponse() {
            Envelope env = BinaryMessages.createErrorResponse(4, "boom", ALLOC);
            try {
                Assertions.assertEquals(Opcode.ERROR, env.getHeader().opcode());
                Assertions.assertEquals(4, env.getHeader().streamId());
                String decoded = BufferUtil.readString(env.getBody());
                Assertions.assertEquals("boom", decoded);
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("createErrorResponse with null message yields empty string")
        void testErrorResponseNull() {
            Envelope env = BinaryMessages.createErrorResponse(1, null, ALLOC);
            try {
                Assertions.assertEquals("", BufferUtil.readString(env.getBody()));
            } finally {
                releaseEnvelope(env);
            }
        }

        @ParameterizedTest(name = "msg=\"{0}\"")
        @ValueSource(strings = {"", "e", "short", "a much longer error message body"})
        @DisplayName("createErrorResponse round trip")
        void testErrorResponseVarious(String msg) {
            Envelope env = BinaryMessages.createErrorResponse(1, msg, ALLOC);
            try {
                Assertions.assertEquals(msg, BufferUtil.readString(env.getBody()));
            } finally {
                releaseEnvelope(env);
            }
        }
    }

    @Nested
    @DisplayName("Event Envelope")
    class EventEnvelopeTests {

        private static Event newEvent(UUID eventId, EventType type, String payload) {
            return new Event(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    eventId,
                    type,
                    payload,
                    Instant.ofEpochMilli(Uuids.unixTimestamp(eventId)));
        }

        @Test
        @DisplayName("round trip preserves event fields")
        void testRoundTrip() {
            UUID eventId = Uuids.timeBased();
            Event original = newEvent(eventId, EventType.MESSAGE_CREATED, "hello world");

            Envelope env = BinaryMessages.createEventEnvelope(original, 9, ALLOC);
            try {
                Assertions.assertEquals(Opcode.EVENT, env.getHeader().opcode());
                Assertions.assertEquals(9, env.getHeader().streamId());

                Event decoded = BinaryMessages.decodeEvent(env.getBody());
                Assertions.assertEquals(original.senderId(), decoded.senderId());
                Assertions.assertEquals(original.conversationId(), decoded.conversationId());
                Assertions.assertEquals(original.eventId(), decoded.eventId());
                Assertions.assertEquals(original.eventType(), decoded.eventType());
                Assertions.assertEquals(original.payload(), decoded.payload());
                Assertions.assertEquals(original.createdAt(), decoded.createdAt());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName(
                "wire format: short-string type + uuid id + uuid conv + uuid sender + long-string payload")
        void testWireFormat() {
            UUID eventId = Uuids.timeBased();
            Event event = newEvent(eventId, EventType.MESSAGE_CREATED, "hi");

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                ByteBuf body = env.getBody();
                Assertions.assertEquals(
                        EventType.MESSAGE_CREATED.name(), BufferUtil.readString(body));
                Assertions.assertEquals(event.eventId(), BufferUtil.readUUID(body));
                Assertions.assertEquals(event.conversationId(), BufferUtil.readUUID(body));
                Assertions.assertEquals(event.senderId(), BufferUtil.readUUID(body));
                Assertions.assertEquals("hi", BufferUtil.readLongString(body));
                Assertions.assertEquals(0, body.readableBytes());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("decoded createdAt is derived from the time-based UUID")
        void testTimestampDerivedFromUuid() {
            UUID eventId = Uuids.timeBased();
            long expectedMillis = Uuids.unixTimestamp(eventId);
            Event event = newEvent(eventId, EventType.MESSAGE_CREATED, "p");

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Event decoded = BinaryMessages.decodeEvent(env.getBody());
                Assertions.assertEquals(expectedMillis, decoded.createdAt().toEpochMilli());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("caller-supplied createdAt is ignored; decoder derives it from eventId")
        void testCallerTimestampNotOnWire() {
            UUID eventId = Uuids.timeBased();
            long derivedMillis = Uuids.unixTimestamp(eventId);

            // Explicitly set a bogus createdAt
            Event event =
                    new Event(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            eventId,
                            EventType.MESSAGE_CREATED,
                            "p",
                            Instant.ofEpochMilli(0L));

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Event decoded = BinaryMessages.decodeEvent(env.getBody());
                Assertions.assertEquals(derivedMillis, decoded.createdAt().toEpochMilli());
                Assertions.assertNotEquals(event.createdAt(), decoded.createdAt());
            } finally {
                releaseEnvelope(env);
            }
        }

        @ParameterizedTest(name = "payload=\"{0}\"")
        @ValueSource(
                strings = {"", "hi", "a longer payload with unicode 🎉 and spaces", "日本語のメッセージ"})
        @DisplayName("round trip preserves payloads including unicode")
        void testVariousPayloads(String payload) {
            UUID eventId = Uuids.timeBased();
            Event event = newEvent(eventId, EventType.MESSAGE_CREATED, payload);

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Assertions.assertEquals(
                        payload, BinaryMessages.decodeEvent(env.getBody()).payload());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("null payload is encoded and decoded as empty string")
        void testNullPayload() {
            UUID eventId = Uuids.timeBased();
            Event event = newEvent(eventId, EventType.MESSAGE_CREATED, null);

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Assertions.assertEquals("", BinaryMessages.decodeEvent(env.getBody()).payload());
            } finally {
                releaseEnvelope(env);
            }
        }

        @ParameterizedTest(name = "type={0}")
        @EnumSource(EventType.class)
        @DisplayName("round trip preserves every EventType")
        void testAllEventTypes(EventType type) {
            UUID eventId = Uuids.timeBased();
            Event event = newEvent(eventId, type, "p");

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Assertions.assertEquals(
                        type, BinaryMessages.decodeEvent(env.getBody()).eventType());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("decodeEvent rejects non-time-based eventId (driver requires v1)")
        void testDecodeEventRejectsNonTimeBasedId() {
            UUID nonV1 = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);

            BufferUtil.writeString(EventType.MESSAGE_CREATED.name(), buffer);
            BufferUtil.writeUUID(nonV1, buffer);
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            BufferUtil.writeLongString("payload", buffer);

            Assertions.assertThrows(
                    IllegalArgumentException.class, () -> BinaryMessages.decodeEvent(buffer));
        }

        @Test
        @DisplayName("round trip preserves extreme UUID values for sender and conversation")
        void testExtremeUuids() {
            UUID eventId = Uuids.timeBased();

            UUID senderId = new UUID(Long.MAX_VALUE, Long.MIN_VALUE);
            UUID convId = new UUID(0L, 0L);

            Event event =
                    new Event(
                            senderId,
                            convId,
                            eventId,
                            EventType.MESSAGE_DELETED,
                            "payload",
                            Instant.ofEpochMilli(Uuids.unixTimestamp(eventId)));

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Event decoded = BinaryMessages.decodeEvent(env.getBody());
                Assertions.assertEquals(eventId, decoded.eventId());
                Assertions.assertEquals(convId, decoded.conversationId());
                Assertions.assertEquals(senderId, decoded.senderId());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("decodeEvent rejects unknown event type name")
        void testUnknownEventType() {
            UUID eventId = Uuids.timeBased();
            BufferUtil.writeString("NOT_A_REAL_TYPE", buffer);
            BufferUtil.writeUUID(eventId, buffer);
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            BufferUtil.writeLongString("payload", buffer);

            Assertions.assertThrows(
                    IllegalArgumentException.class, () -> BinaryMessages.decodeEvent(buffer));
        }

        @Test
        @DisplayName("decodeEvent rejects empty event type name")
        void testEmptyEventType() {
            UUID eventId = Uuids.timeBased();
            BufferUtil.writeString("", buffer);
            BufferUtil.writeUUID(eventId, buffer);
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            BufferUtil.writeUUID(UUID.randomUUID(), buffer);
            BufferUtil.writeLongString("payload", buffer);

            Assertions.assertThrows(
                    IllegalArgumentException.class, () -> BinaryMessages.decodeEvent(buffer));
        }

        @ParameterizedTest(name = "streamId={0}")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 42, Integer.MAX_VALUE})
        @DisplayName("createEventEnvelope preserves streamId")
        void testStreamIds(int streamId) {
            UUID eventId = Uuids.timeBased();
            Event event = newEvent(eventId, EventType.MESSAGE_CREATED, "p");

            Envelope env = BinaryMessages.createEventEnvelope(event, streamId, ALLOC);
            try {
                Assertions.assertEquals(streamId, env.getHeader().streamId());
            } finally {
                releaseEnvelope(env);
            }
        }

        @Test
        @DisplayName("header bodyLength matches the serialized event size")
        void testBodyLength() {
            UUID eventId = Uuids.timeBased();
            Event event = newEvent(eventId, EventType.MESSAGE_READ, "hello");

            Envelope env = BinaryMessages.createEventEnvelope(event, 1, ALLOC);
            try {
                Assertions.assertEquals(
                        env.getBody().readableBytes(), env.getHeader().bodyLength());
            } finally {
                releaseEnvelope(env);
            }
        }
    }
}
