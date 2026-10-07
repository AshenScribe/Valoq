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
package server.model;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.IllegalReferenceCountException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import protocol.Envelope;
import protocol.Opcode;

@DisplayName("RoutedMessage")
class RoutedMessageTest {

    private static final ObjectMapper MAPPER = Envelope.getMapper();

    private static Envelope envelopeWithBody(Opcode opcode, int streamId, byte[] payload) {
        ByteBuf body =
                payload.length == 0 ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(payload);
        return Envelope.create(opcode, streamId, body);
    }

    private static RoutedMessage sampleMessage() {
        UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Envelope envelope =
                envelopeWithBody(Opcode.SEND, 42, "hello".getBytes(StandardCharsets.UTF_8));
        return new RoutedMessage(userId, envelope);
    }

    @Nested
    @DisplayName("Construction and accessors")
    class Construction {

        @Test
        @DisplayName("record exposes userId and envelope via accessors")
        void accessors() {
            UUID userId = UUID.randomUUID();
            Envelope env = Envelope.createEmpty(Opcode.INIT, 1);
            RoutedMessage msg = new RoutedMessage(userId, env);

            Assertions.assertEquals(userId, msg.userId());
            Assertions.assertSame(env, msg.envelope());
        }

        @Test
        @DisplayName("record equality and hashCode are value-based")
        void equalsAndHashCode() {
            UUID userId = UUID.randomUUID();
            Envelope env = Envelope.createEmpty(Opcode.SYNC, 7);

            RoutedMessage a = new RoutedMessage(userId, env);
            RoutedMessage b = new RoutedMessage(userId, env);

            Assertions.assertEquals(a, b);
            Assertions.assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("records with different userId are not equal")
        void notEqualDifferentUser() {
            Envelope env = Envelope.createEmpty(Opcode.SYNC, 7);
            RoutedMessage a = new RoutedMessage(UUID.randomUUID(), env);
            RoutedMessage b = new RoutedMessage(UUID.randomUUID(), env);

            Assertions.assertNotEquals(a, b);
        }

        @Test
        @DisplayName("record accepts null envelope reference (documented behavior)")
        void nullEnvelopeAllowedInRecord() {
            UUID userId = UUID.randomUUID();
            RoutedMessage msg = new RoutedMessage(userId, null);
            Assertions.assertNull(msg.envelope());
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("toJson produces valid JSON with userId and envelope fields")
        void toJsonContainsExpectedFields() throws Exception {
            RoutedMessage msg = sampleMessage();
            String json = msg.toJson();

            Assertions.assertNotNull(json);
            var node = MAPPER.readTree(json);
            Assertions.assertTrue(node.has("userId"));
            Assertions.assertTrue(node.has("envelope"));
            Assertions.assertEquals(
                    "11111111-2222-3333-4444-555555555555", node.get("userId").asText());
        }

        @Test
        @DisplayName("toJson encodes body as Base64")
        void toJsonEncodesBodyAsBase64() throws Exception {
            RoutedMessage msg = sampleMessage();
            String json = msg.toJson();

            String expected =
                    Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

            var bodyNode = MAPPER.readTree(json).get("envelope").get("body");
            Assertions.assertEquals(expected, bodyNode.asText());
        }

        @Test
        @DisplayName("toJson for empty body produces empty string body")
        void toJsonEmptyBody() throws Exception {
            RoutedMessage msg =
                    new RoutedMessage(UUID.randomUUID(), Envelope.createEmpty(Opcode.ACK, 3));
            String json = msg.toJson();

            var bodyNode = MAPPER.readTree(json).get("envelope").get("body");
            Assertions.assertEquals("", bodyNode.asText());
        }

        @Test
        @DisplayName("toJson includes full Header fields")
        void toJsonIncludesHeaderFields() throws Exception {
            RoutedMessage msg = sampleMessage();
            var header = MAPPER.readTree(msg.toJson()).get("envelope").get("header");

            Assertions.assertEquals(Envelope.MAGIC, (byte) header.get("magic").asInt());
            Assertions.assertEquals(Envelope.VERSION, (byte) header.get("version").asInt());
            Assertions.assertEquals(Opcode.SEND.getCode(), (byte) header.get("opcode").asInt());
            Assertions.assertEquals(0, header.get("flags").asInt());
            Assertions.assertEquals(42, header.get("streamId").asInt());
            Assertions.assertEquals(5, header.get("bodyLength").asInt());
        }

        @Test
        @DisplayName("toJson is idempotent for the same message")
        void toJsonIdempotent() {
            RoutedMessage msg = sampleMessage();
            Assertions.assertEquals(msg.toJson(), msg.toJson());
        }
    }

    @Nested
    @DisplayName("JSON deserialization")
    class JsonDeserialization {

        @Test
        @DisplayName("fromJson(toJson) round-trips userId and envelope metadata")
        void roundTripMetadata() {
            RoutedMessage original = sampleMessage();
            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());

            Assertions.assertEquals(original.userId(), restored.userId());
            Assertions.assertEquals(
                    original.envelope().getHeader(), restored.envelope().getHeader());
        }

        @Test
        @DisplayName("fromJson reconstructs body bytes exactly")
        void roundTripBodyBytes() {
            byte[] payload = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
            RoutedMessage original =
                    new RoutedMessage(
                            UUID.randomUUID(), envelopeWithBody(Opcode.EVENT, 9, payload));

            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());

            ByteBuf body = restored.envelope().getBody();
            byte[] out = new byte[body.readableBytes()];
            body.getBytes(body.readerIndex(), out);
            Assertions.assertArrayEquals(payload, out);
        }

        @Test
        @DisplayName("fromJson round-trips binary payload (non-UTF8 bytes)")
        void roundTripBinaryPayload() {
            byte[] payload = new byte[] {0x00, (byte) 0xFF, (byte) 0x80, 0x01, 0x7F};
            RoutedMessage original =
                    new RoutedMessage(
                            UUID.randomUUID(), envelopeWithBody(Opcode.EVENT, 10, payload));

            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());
            ByteBuf body = restored.envelope().getBody();
            byte[] out = new byte[body.readableBytes()];
            body.getBytes(body.readerIndex(), out);
            Assertions.assertArrayEquals(payload, out);
        }

        @Test
        @DisplayName("fromJson handles empty body producing EMPTY_BUFFER")
        void emptyBodyRoundTrip() {
            RoutedMessage original =
                    new RoutedMessage(
                            UUID.randomUUID(), Envelope.createEmpty(Opcode.ACK_DELIVERED, 11));

            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());

            Assertions.assertEquals(0, restored.envelope().getBody().readableBytes());
        }

        @Test
        @DisplayName("fromJson preserves all Opcode values")
        void roundTripAllOpcodes() {
            for (Opcode op : Opcode.values()) {
                RoutedMessage original =
                        new RoutedMessage(UUID.randomUUID(), Envelope.createEmpty(op, 1));
                RoutedMessage restored = RoutedMessage.fromJson(original.toJson());
                Assertions.assertEquals(
                        op, restored.envelope().getHeader().opcode(), "Opcode mismatch for " + op);
            }
        }

        @Test
        @DisplayName("fromJson preserves negative and large streamIds")
        void roundTripStreamIds() {
            int[] streamIds = {
                Envelope.EVENT_STREAM_ID, 0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE
            };
            for (int id : streamIds) {
                RoutedMessage original =
                        new RoutedMessage(UUID.randomUUID(), Envelope.createEmpty(Opcode.SYNC, id));
                RoutedMessage restored = RoutedMessage.fromJson(original.toJson());
                Assertions.assertEquals(id, restored.envelope().getHeader().streamId());
            }
        }

        @Test
        @DisplayName("fromJson preserves userId formatting")
        void userIdRoundTrip() {
            UUID id = UUID.fromString("deadbeef-dead-beef-dead-beefdeadbeef");
            RoutedMessage original = new RoutedMessage(id, Envelope.createEmpty(Opcode.INIT, 2));
            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());
            Assertions.assertEquals(id, restored.userId());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("fromJson throws RuntimeException on malformed JSON")
        void malformedJson() {
            RuntimeException ex =
                    Assertions.assertThrows(
                            RuntimeException.class, () -> RoutedMessage.fromJson("{not-json"));
            Assertions.assertEquals("Failed to deserialize RoutedMessage", ex.getMessage());
            Assertions.assertNotNull(ex.getCause());
        }

        @Test
        @DisplayName("fromJson throws RuntimeException on empty string")
        void emptyInput() {
            RuntimeException ex =
                    Assertions.assertThrows(
                            RuntimeException.class, () -> RoutedMessage.fromJson(""));
            Assertions.assertEquals("Failed to deserialize RoutedMessage", ex.getMessage());
        }

        @Test
        @DisplayName("fromJson throws RuntimeException on unknown opcode byte")
        void unknownOpcode() {
            String json =
                    """
                    {
                      "userId": "11111111-2222-3333-4444-555555555555",
                      "envelope": {
                        "header": {
                          "magic": 86, "version": 1, "opcode": 99,
                          "flags": 0, "streamId": 1, "bodyLength": 0
                        },
                        "body": ""
                      }
                    }
                    """;
            RuntimeException ex =
                    Assertions.assertThrows(
                            RuntimeException.class, () -> RoutedMessage.fromJson(json));
            Assertions.assertEquals("Failed to deserialize RoutedMessage", ex.getMessage());
        }

        @Test
        @DisplayName("fromJson throws RuntimeException on invalid userId format")
        void invalidUserId() {
            String json =
                    """
                    {
                      "userId": "not-a-uuid",
                      "envelope": {
                        "header": {
                          "magic": 86, "version": 1, "opcode": 1,
                          "flags": 0, "streamId": 1, "bodyLength": 0
                        },
                        "body": ""
                      }
                    }
                    """;
            Assertions.assertThrows(RuntimeException.class, () -> RoutedMessage.fromJson(json));
        }

        @Test
        @DisplayName("fromJson throws RuntimeException on invalid Base64 body")
        void invalidBase64Body() {
            String json =
                    """
                    {
                      "userId": "11111111-2222-3333-4444-555555555555",
                      "envelope": {
                        "header": {
                          "magic": 86, "version": 1, "opcode": 3,
                          "flags": 0, "streamId": 1, "bodyLength": 4
                        },
                        "body": "@@@@not-base64@@@@"
                      }
                    }
                    """;
            Assertions.assertThrows(RuntimeException.class, () -> RoutedMessage.fromJson(json));
        }

        @Test
        @DisplayName("fromJson accepts null body as empty buffer")
        void nullBody() {
            String json =
                    """
                    {
                      "userId": "11111111-2222-3333-4444-555555555555",
                      "envelope": {
                        "header": {
                          "magic": 86, "version": 1, "opcode": 3,
                          "flags": 0, "streamId": 1, "bodyLength": 0
                        },
                        "body": null
                      }
                    }
                    """;
            RoutedMessage msg = RoutedMessage.fromJson(json);
            Assertions.assertEquals(0, msg.envelope().getBody().readableBytes());
        }
    }

    @Nested
    @DisplayName("Envelope lifecycle integration")
    class Lifecycle {

        @Test
        @DisplayName("envelope.duplicate() shares body bytes and increments refCnt")
        void duplicateSharesBody() {
            RoutedMessage original = sampleMessage();
            Envelope dup = original.envelope().duplicate();

            try {
                Assertions.assertEquals(2, original.envelope().getBody().refCnt());
                Assertions.assertEquals(original.envelope().getHeader(), dup.getHeader());

                byte[] out = new byte[dup.getBody().readableBytes()];
                dup.getBody().getBytes(dup.getBody().readerIndex(), out);
                Assertions.assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), out);
            } finally {
                dup.release();
            }
            Assertions.assertEquals(1, original.envelope().getBody().refCnt());
        }

        @Test
        @DisplayName("envelope.retain() increments refCnt and returns same instance")
        void retainIncrementsRefCnt() {
            RoutedMessage msg = sampleMessage();
            Envelope env = msg.envelope();

            try {
                Assertions.assertSame(env, env.retain());
                Assertions.assertEquals(2, env.getBody().refCnt());
            } finally {
                env.release();
            }
            Assertions.assertEquals(1, env.getBody().refCnt());
        }

        @Test
        @DisplayName("serializing a released envelope throws IllegalReferenceCountException")
        void serializeAfterReleaseThrows() {
            RoutedMessage msg = sampleMessage();
            msg.envelope().release();

            RuntimeException ex = Assertions.assertThrows(RuntimeException.class, msg::toJson);
            Assertions.assertTrue(ex.getCause() instanceof JsonMappingException);
            Assertions.assertTrue(
                    ex.getCause().getCause() instanceof IllegalReferenceCountException);
        }

        @Test
        @DisplayName("round-trip produces an independent ByteBuf instance")
        void roundTripProducesIndependentBuffer() {
            RoutedMessage original = sampleMessage();
            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());

            Assertions.assertNotSame(original.envelope().getBody(), restored.envelope().getBody());
            Assertions.assertEquals(1, restored.envelope().getBody().refCnt());
        }
    }

    @Nested
    @DisplayName("JSON schema stability")
    class Schema {

        @Test
        @DisplayName("envelope body is serialized as a JSON string, not an object")
        void bodyIsString() throws Exception {
            RoutedMessage msg = sampleMessage();
            var body = MAPPER.readTree(msg.toJson()).get("envelope").get("body");
            Assertions.assertTrue(body.isTextual(), "body must be a Base64 JSON string");
        }

        @Test
        @DisplayName("header opcode is serialized as a number")
        void opcodeIsNumber() throws Exception {
            RoutedMessage msg = sampleMessage();
            var opcode = MAPPER.readTree(msg.toJson()).get("envelope").get("header").get("opcode");
            Assertions.assertTrue(opcode.isNumber());
            Assertions.assertEquals(Opcode.SEND.getCode(), (byte) opcode.asInt());
        }

        @Test
        @DisplayName("userId is serialized as a string")
        void userIdIsString() throws Exception {
            RoutedMessage msg = sampleMessage();
            var userId = MAPPER.readTree(msg.toJson()).get("userId");
            Assertions.assertTrue(userId.isTextual());
        }

        @Test
        @DisplayName("fromJson tolerates extra unknown fields")
        void toleratesUnknownFields() {
            String json =
                    """
                    {
                      "userId": "11111111-2222-3333-4444-555555555555",
                      "extra": "ignored",
                      "envelope": {
                        "header": {
                          "magic": 86, "version": 1, "opcode": 3,
                          "flags": 0, "streamId": 1, "bodyLength": 0
                        },
                        "body": "",
                        "anotherExtra": 123
                      }
                    }
                    """;
            Assertions.assertDoesNotThrow(
                    () -> {
                        try {
                            RoutedMessage.fromJson(json);
                        } catch (RuntimeException e) {
                            throw new AssertionError(
                                    "Unknown fields caused failure; configure FAIL_ON_UNKNOWN_PROPERTIES=false",
                                    e);
                        }
                    });
        }
    }

    @Nested
    @DisplayName("Stress")
    class Stress {

        @Test
        @DisplayName("1000 sequential round-trips preserve payload integrity")
        void manyRoundTrips() {
            for (int i = 0; i < 1000; i++) {
                byte[] payload = ("payload-" + i).getBytes(StandardCharsets.UTF_8);
                RoutedMessage original =
                        new RoutedMessage(
                                UUID.randomUUID(), envelopeWithBody(Opcode.EVENT, i, payload));

                RoutedMessage restored = RoutedMessage.fromJson(original.toJson());
                ByteBuf body = restored.envelope().getBody();
                byte[] out = new byte[body.readableBytes()];
                body.getBytes(body.readerIndex(), out);
                Assertions.assertArrayEquals(payload, out);
            }
        }

        @Test
        @DisplayName("1 MB payload survives round-trip")
        void largePayload() {
            byte[] payload = new byte[1 << 20];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);

            RoutedMessage original =
                    new RoutedMessage(
                            UUID.randomUUID(), envelopeWithBody(Opcode.EVENT, 1, payload));

            RoutedMessage restored = RoutedMessage.fromJson(original.toJson());
            ByteBuf body = restored.envelope().getBody();
            byte[] out = new byte[body.readableBytes()];
            body.getBytes(body.readerIndex(), out);
            Assertions.assertArrayEquals(payload, out);
        }
    }
}
