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
package server;

import base.BaseIntegrationTest;
import client.MessageTestClient;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import database.BucketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import protocol.BinaryMessages;
import protocol.BufferUtil;
import protocol.Envelope;
import protocol.Opcode;
import server.model.Event;
import server.model.EventType;

class MessageServerE2ETest extends BaseIntegrationTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    private static final String CREATED_AT = Instant.now().toString();
    private static final String TIME_BUCKET = BucketUtils.toTimeBucket(Instant.parse(CREATED_AT));
    private static final UUID ALICE_UUID = UUID.randomUUID();
    private static final UUID BOB_UUID = UUID.randomUUID();

    @Nested
    @DisplayName("Core Routing & Cassandra Persistence")
    class CoreRoutingTests {

        @Test
        @DisplayName("End-to-end messaging routes between online clients and persists to Cassandra")
        void endToEndMessagingAndPersistenceFlow() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                Assertions.assertEquals("SUCCESS", bob.init(BOB_UUID));

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "SGVsbG8=");

                Event bobEvent = bob.readEvent();
                assertMessageEvent(bobEvent, CONVERSATION_ID, ALICE_UUID, "SGVsbG8=");

                Event aliceEvent = alice.readEvent();
                assertMessageEvent(aliceEvent, CONVERSATION_ID, ALICE_UUID, "SGVsbG8=");

                bob.sendMessage(CONVERSATION_ID, CREATED_AT, "V29ybGQ=");

                Event aliceEvent2 = alice.readEvent();
                assertMessageEvent(aliceEvent2, CONVERSATION_ID, BOB_UUID, "V29ybGQ=");

                Event bobEvent2 = bob.readEvent();
                assertMessageEvent(bobEvent2, CONVERSATION_ID, BOB_UUID, "V29ybGQ=");

                Row row =
                        awaitRow(
                                """
                                SELECT event_id, event_type, actor_id, payload
                                FROM valoq_messages.events
                                WHERE conversation_id = %s
                                  AND time_bucket = '%s'
                                  AND hash_bucket = 0
                                LIMIT 1
                                """
                                        .formatted(CONVERSATION_ID, TIME_BUCKET));

                Assertions.assertNotNull(row, "Message must be persisted in Cassandra");

                Assertions.assertEquals("MESSAGE_CREATED", row.getString("event_type"));
            }
        }

        @Test
        @DisplayName(
                "Sending to an offline conversation member persists the event and does not fail")
        void sendToOfflineMemberPersistsEvent() throws Exception {
            UUID offlineUserId = UUID.randomUUID();
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, offlineUserId);

            try (MessageTestClient alice = connect()) {

                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "SGVsbG8=");

                Event event = alice.readEvent();
                assertMessageEvent(event, CONVERSATION_ID, ALICE_UUID, "SGVsbG8=");

                Row row =
                        awaitRow(
                                """
                                SELECT event_id, event_type, actor_id, payload
                                FROM valoq_messages.events
                                WHERE conversation_id = %s
                                  AND time_bucket = '%s'
                                  AND hash_bucket = 0
                                LIMIT 1
                                """
                                        .formatted(CONVERSATION_ID, TIME_BUCKET));

                Assertions.assertNotNull(row, "Offline member message must be persisted");

                Assertions.assertEquals("MESSAGE_CREATED", row.getString("event_type"));

                Assertions.assertEquals(ALICE_UUID, row.getUuid("actor_id"));

                Assertions.assertEquals("SGVsbG8=", row.getString("payload"));
            }
        }

        @Test
        @DisplayName("Messages in a conversation are delivered only to its members")
        void multiUserRoutingIsolation() throws Exception {
            UUID otherConversationId = UUID.randomUUID();
            UUID charlieUuid = UUID.randomUUID();

            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);
            addConversationMember(otherConversationId, charlieUuid);

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect();
                    MessageTestClient charlie = connect()) {

                alice.init(ALICE_UUID);
                bob.init(BOB_UUID);
                charlie.init(charlieUuid);

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "SGVsbG8gQm9i");

                Event bobEvent = bob.readEvent();
                assertMessageEvent(bobEvent, CONVERSATION_ID, ALICE_UUID, "SGVsbG8gQm9i");

                Assertions.assertNull(
                        charlie.readEvent(Duration.ofMillis(200)),
                        "Charlie is not a member of the conversation");
            }
        }

        @Test
        @DisplayName("Client sending a message to its conversation receives the event")
        void selfMessagingFlow() throws Exception {
            addConversationMember(CONVERSATION_ID, ALICE_UUID);

            try (MessageTestClient alice = connect()) {

                alice.init(ALICE_UUID);

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "U2VsZi1tZXNzYWdl");

                Event event = alice.readEvent();
                assertMessageEvent(event, CONVERSATION_ID, ALICE_UUID, "U2VsZi1tZXNzYWdl");
            }
        }
    }

    @Nested
    @DisplayName("Protocol Edge Cases & Error Recovery")
    class ProtocolEdgeCaseTests {

        @Test
        @DisplayName("Invalid INIT closes socket immediately")
        void invalidInitClosesSocket() throws Exception {
            try (MessageTestClient client = connect()) {

                client.sendInvalidInit("bad_format");

                Envelope response = client.readEnvelope();
                Assertions.assertNotNull(response);
                try {
                    Assertions.assertEquals(Opcode.ERROR, response.getHeader().opcode());
                    Assertions.assertEquals(
                            "INVALID", protocol.BufferUtil.readString(response.getBody()));
                } finally {
                    response.release();
                }

                Assertions.assertTrue(client.isClosedByServer());
            }
        }

        @Test
        @DisplayName("Malformed SEND returns ERROR format and keeps connection open")
        void malformedSendReturnsErrorAndKeepsConnectionAlive() throws Exception {

            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init(ALICE_UUID);
                bob.init(BOB_UUID);

                alice.sendMalformedSend();

                Envelope response = alice.readEnvelope();
                Assertions.assertNotNull(response);
                try {
                    Assertions.assertEquals(Opcode.ERROR, response.getHeader().opcode());
                    Assertions.assertEquals(
                            "ERROR Invalid SEND format",
                            protocol.BufferUtil.readString(response.getBody()));
                } finally {
                    response.release();
                }

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "U3RpbGwgYWxpdmU=");

                Event event = bob.readEvent();
                assertMessageEvent(event, CONVERSATION_ID, ALICE_UUID, "U3RpbGwgYWxpdmU=");
            }
        }
    }

    @Nested
    @DisplayName("TCP Framing & Ordering")
    class TcpFramingTests {

        @Test
        @DisplayName("Rapid burst of messages must arrive in event order")
        void rapidBurstMessageOrdering() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init(ALICE_UUID);
                bob.init(BOB_UUID);

                int burstCount = 5;

                for (int i = 0; i < burstCount; i++) {
                    alice.sendMessage(CONVERSATION_ID, CREATED_AT, "bXNnX" + i);
                }

                for (int i = 0; i < burstCount; i++) {
                    Event event = bob.readEvent();
                    assertMessageEvent(event, CONVERSATION_ID, ALICE_UUID, "bXNnX" + i);
                }
            }
        }

        @Test
        @DisplayName("Pipelined TCP writes must be processed independently")
        void pipelinedCommandsInSinglePacket() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                bob.init(BOB_UUID);
                alice.writeInit(ALICE_UUID);
                alice.writeSendMessage(CONVERSATION_ID, CREATED_AT, "UGlwcGVsaW5lZA==");
                alice.flush();

                Envelope ready = alice.readEnvelope();
                Assertions.assertNotNull(ready);
                try {
                    Assertions.assertEquals(Opcode.READY, ready.getHeader().opcode());
                } finally {
                    ready.release();
                }

                Event event = bob.readEvent();
                assertMessageEvent(event, CONVERSATION_ID, ALICE_UUID, "UGlwcGVsaW5lZA==");
            }
        }
    }

    @Test
    @DisplayName("Full Delivery & Read Receipt Lifecycle with Watermarking")
    void testReceiptLifecycle() throws Exception {
        addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

        try (MessageTestClient alice = connect();
                MessageTestClient bob = connect()) {

            alice.init(ALICE_UUID);
            bob.init(BOB_UUID);
            alice.sendMessage(CONVERSATION_ID, CREATED_AT, "Receipt Test Message");
            UUID messageEventId = alice.readAck();
            Assertions.assertNotNull(messageEventId, "Alice must receive server ACK with eventId");
            Event bobMessage = bob.readEvent();
            Assertions.assertNotNull(bobMessage);
            Assertions.assertEquals(messageEventId, bobMessage.eventId());
            Event aliceEcho = alice.readEvent();
            Assertions.assertNotNull(aliceEcho);
            bob.ackDeliveredWatermark(CONVERSATION_ID, messageEventId);

            Event aliceDeliveryReceipt = alice.readEvent();
            Assertions.assertNotNull(aliceDeliveryReceipt);
            Assertions.assertEquals(EventType.MESSAGE_DELIVERED, aliceDeliveryReceipt.eventType());
            Assertions.assertEquals(BOB_UUID, aliceDeliveryReceipt.senderId());
            bob.ackReadWatermark(CONVERSATION_ID, messageEventId);

            Event aliceReadReceipt = alice.readEvent();
            Assertions.assertNotNull(aliceReadReceipt);
            Assertions.assertEquals(EventType.MESSAGE_READ, aliceReadReceipt.eventType());
            Assertions.assertEquals(BOB_UUID, aliceReadReceipt.senderId());
        }
    }

    private static void assertMessageEvent(
            Event event, UUID conversationId, UUID senderId, String payload) {
        Assertions.assertNotNull(event);
        Assertions.assertEquals(conversationId, event.conversationId());
        Assertions.assertEquals(senderId, event.senderId());
        Assertions.assertEquals("MESSAGE_CREATED", event.eventType().name());
        Assertions.assertEquals(payload, event.payload());
    }

    @Nested
    @DisplayName("Reconnection, Backpressure & Fuzz Resilience")
    class MessageServerResilienceE2ETests {

        private static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024;
        private static final int HIGH_WATERMARK = 64;
        private static final Duration LONG_TIMEOUT = Duration.ofSeconds(10);

        @Test
        @DisplayName("reconnect and SYNC recover all messages created while offline")
        void reconnectAndSyncRecoverMissedMessages() throws Exception {
            UUID offlineUser = UUID.randomUUID();
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, offlineUser);
            UUID cursor = Uuids.startOf(System.currentTimeMillis());
            int count = 20;

            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                for (int i = 0; i < count; i++) {
                    alice.writeSendMessage(
                            CONVERSATION_ID,
                            Instant.now().plusMillis(i).toString(),
                            "offline-" + i);
                }
                alice.flush();
                for (int i = 0; i < count; i++) {
                    Assertions.assertNotNull(alice.readAck());
                }
            }

            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.init(offlineUser);
                client.sendSync(cursor);
                List<Event> events = client.readEvents(count, LONG_TIMEOUT);
                Assertions.assertEquals(count, events.size());
                for (int i = 0; i < count; i++) {
                    Assertions.assertEquals("offline-" + i, events.get(i).payload());
                    Assertions.assertEquals(CONVERSATION_ID, events.get(i).conversationId());
                    Assertions.assertEquals(ALICE_UUID, events.get(i).senderId());
                }
            }
        }

        @Test
        @DisplayName("SYNC respects the supplied cursor and excludes the cursor event")
        void syncRespectsCursor() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);
            UUID firstId;

            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                alice.sendMessage(CONVERSATION_ID, Instant.now().toString(), "first");
                firstId = alice.readAck();
                Assertions.assertNotNull(firstId);
                alice.sendMessage(
                        CONVERSATION_ID, Instant.now().plusMillis(1).toString(), "second");
                Assertions.assertNotNull(alice.readAck());
            }

            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.init(BOB_UUID);
                client.sendSync(firstId);
                List<Event> events = client.readEvents(1, LONG_TIMEOUT);
                Assertions.assertEquals(1, events.size());
                Assertions.assertEquals("second", events.get(0).payload());
            }
        }

        @Test
        @DisplayName("SYNC after the latest event returns no event")
        void syncAfterLatestEventReturnsEmpty() throws Exception {
            addConversationMember(CONVERSATION_ID, ALICE_UUID);
            UUID latest;

            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                alice.sendMessage(CONVERSATION_ID, Instant.now().toString(), "latest");
                latest = alice.readAck();
                Assertions.assertNotNull(latest);
            }

            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.init(ALICE_UUID);
                client.sendSync(latest);
                Assertions.assertNull(client.readEvent(Duration.ofMillis(500)));
            }
        }

        @Test
        @DisplayName("repeated disconnect and reconnect cycles preserve every persisted event")
        void repeatedReconnectCyclesRecoverAllEvents() throws Exception {
            UUID offlineUser = UUID.randomUUID();
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, offlineUser);
            UUID cursor = Uuids.startOf(System.currentTimeMillis());

            for (int i = 0; i < 5; i++) {
                try (MessageTestClient alice = connect()) {
                    Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                    alice.sendMessage(
                            CONVERSATION_ID, Instant.now().plusMillis(i).toString(), "cycle-" + i);
                    Assertions.assertNotNull(alice.readAck());
                }
            }

            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.init(offlineUser);
                client.sendSync(cursor);
                List<Event> events = client.readEvents(5, LONG_TIMEOUT);
                Assertions.assertEquals(5, events.size());
                for (int i = 0; i < 5; i++) {
                    Assertions.assertEquals("cycle-" + i, events.get(i).payload());
                }
            }
        }

        @Test
        @DisplayName("abrupt client disconnect does not poison subsequent connections")
        void abruptDisconnectDoesNotPoisonServer() throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(randomGarbage(1024));
            }

            try (MessageTestClient client = connect()) {
                Assertions.assertEquals("SUCCESS", client.init(ALICE_UUID));
            }
        }

        @Test
        @DisplayName(
                "pipelined burst above the high watermark eventually acknowledges every message")
        void burstCrossesHighWatermark() throws Exception {
            addConversationMember(CONVERSATION_ID, ALICE_UUID);
            int count = HIGH_WATERMARK + 32;

            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                for (int i = 0; i < count; i++) {
                    alice.writeSendMessage(
                            CONVERSATION_ID, Instant.now().plusNanos(i).toString(), "burst-" + i);
                }
                alice.flush();
                Set<UUID> ackIds = readAckIds(alice, count, LONG_TIMEOUT);
                Assertions.assertEquals(count, ackIds.size());
            }
        }

        @Test
        @DisplayName("repeated high watermark cycles recover normal reads")
        void repeatedBackpressureCyclesRecover() throws Exception {
            addConversationMember(CONVERSATION_ID, ALICE_UUID);

            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                for (int cycle = 0; cycle < 5; cycle++) {
                    int count = HIGH_WATERMARK + 8;
                    for (int i = 0; i < count; i++) {
                        alice.writeSendMessage(
                                CONVERSATION_ID,
                                Instant.now().plusNanos(i).toString(),
                                "cycle-" + cycle + "-" + i);
                    }
                    alice.flush();
                    Assertions.assertEquals(count, readAckIds(alice, count, LONG_TIMEOUT).size());
                }

                alice.sendMessage(
                        CONVERSATION_ID,
                        Instant.now().plusSeconds(1).toString(),
                        "after-backpressure");
                Assertions.assertNotNull(alice.readAck());
            }
        }

        @Test
        @DisplayName("multiple clients concurrently pipeline commands without loss")
        void multipleClientsPipelineConcurrently() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);
            UUID charlie = UUID.randomUUID();
            addConversationMember(CONVERSATION_ID, charlie);
            int count = 75;

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect();
                    MessageTestClient c = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                Assertions.assertEquals("SUCCESS", bob.init(BOB_UUID));
                Assertions.assertEquals("SUCCESS", c.init(charlie));

                pipeline(alice, count, "alice-");
                pipeline(bob, count, "bob-");
                pipeline(c, count, "charlie-");

                Assertions.assertEquals(count, readAckIds(alice, count, LONG_TIMEOUT).size());
                Assertions.assertEquals(count, readAckIds(bob, count, LONG_TIMEOUT).size());
                Assertions.assertEquals(count, readAckIds(c, count, LONG_TIMEOUT).size());
            }
        }

        @Test
        @DisplayName("large valid payload burst remains processable")
        void largePayloadBurst() throws Exception {
            addConversationMember(CONVERSATION_ID, ALICE_UUID);
            String payload = "x".repeat(256 * 1024);
            int count = 20;

            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                for (int i = 0; i < count; i++) {
                    alice.writeSendMessage(
                            CONVERSATION_ID, Instant.now().plusNanos(i).toString(), payload + i);
                }
                alice.flush();
                Assertions.assertEquals(count, readAckIds(alice, count, LONG_TIMEOUT).size());
            }
        }

        @ParameterizedTest(name = "garbageSize={0}")
        @org.junit.jupiter.params.provider.ValueSource(
                ints = {1, 2, 3, 4, 8, 16, 32, 128, 1024, 8192})
        @DisplayName("garbage byte sequences do not destabilize the server")
        void garbageSizes(int size) throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(randomGarbage(size));
                client.closeAbruptly();
            }

            assertServerAcceptsConnection();
        }

        @ParameterizedTest(name = "opcode=0x{0}")
        @org.junit.jupiter.params.provider.ValueSource(
                bytes = {
                    (byte) 0x09,
                    (byte) 0x0A,
                    (byte) 0x10,
                    (byte) 0x7F,
                    (byte) 0x80,
                    (byte) 0xFF
                })
        @DisplayName("unknown opcodes close the connection gracefully")
        void unknownOpcodesCloseConnection(byte opcode) throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(
                        frame(Envelope.MAGIC, Envelope.VERSION, opcode, (byte) 0, 1, new byte[0]));
                client.awaitClose(Duration.ofSeconds(3));
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("invalid magic closes the connection gracefully")
        void invalidMagicClosesConnection() throws Exception {
            assertMalformedFrameCloses(
                    frame(
                            (byte) 0x42,
                            Envelope.VERSION,
                            Opcode.SEND.getCode(),
                            (byte) 0,
                            1,
                            new byte[0]));
        }

        @Test
        @DisplayName("invalid version does not crash the server")
        void invalidVersionDoesNotCrashServer() throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(
                        frame(
                                Envelope.MAGIC,
                                (byte) 0x7F,
                                Opcode.SEND.getCode(),
                                (byte) 0,
                                1,
                                new byte[0]));
                client.closeAbruptly();
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("frame one byte above MAX_FRAME_LENGTH is rejected")
        void frameAboveMaximumIsRejected() throws Exception {
            byte[] header =
                    header(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            Opcode.SEND.getCode(),
                            (byte) 0,
                            1,
                            MAX_FRAME_LENGTH - 11);
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(header);
                client.awaitClose(Duration.ofSeconds(5));
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("absurdly large advertised frame is rejected without waiting for its body")
        void absurdFrameLengthIsRejected() throws Exception {
            byte[] header =
                    header(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            Opcode.SEND.getCode(),
                            (byte) 0,
                            1,
                            Integer.MAX_VALUE);
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(header);
                client.awaitClose(Duration.ofSeconds(5));
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("negative advertised frame length does not poison the server")
        void negativeFrameLengthDoesNotPoisonServer() throws Exception {
            byte[] header =
                    header(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            Opcode.SEND.getCode(),
                            (byte) 0,
                            1,
                            -1);
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(header);
                client.closeAbruptly();
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("truncated frame header does not poison subsequent connections")
        void truncatedHeaderDoesNotPoisonServer() throws Exception {
            for (int length = 1; length < 12; length++) {
                try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                    byte[] partial = new byte[length];
                    partial[0] = Envelope.MAGIC;
                    client.sendRawFrame(partial);
                    client.closeAbruptly();
                }
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("truncated frame body does not poison subsequent connections")
        void truncatedBodyDoesNotPoisonServer() throws Exception {
            byte[] header =
                    header(
                            Envelope.MAGIC,
                            Envelope.VERSION,
                            Opcode.SEND.getCode(),
                            (byte) 0,
                            1,
                            1024);
            byte[] body = new byte[16];
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(concat(header, body));
                client.closeAbruptly();
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("malformed SEND returns ERROR and the authenticated connection remains usable")
        void malformedSendDoesNotKillConnection() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init(ALICE_UUID));
                Assertions.assertEquals("SUCCESS", bob.init(BOB_UUID));
                alice.sendMalformedSend();
                Envelope error = alice.readEnvelope();
                Assertions.assertNotNull(error);
                try {
                    Assertions.assertEquals(Opcode.ERROR, error.getHeader().opcode());
                    Assertions.assertEquals(
                            "ERROR Invalid SEND format",
                            protocol.BufferUtil.readString(error.getBody()));
                } finally {
                    error.release();
                }
                alice.sendMessage(CONVERSATION_ID, Instant.now().toString(), "after-malformed");
                Assertions.assertNotNull(alice.readAck());
                assertMessageEvent(bob.readEvent(), CONVERSATION_ID, ALICE_UUID, "after-malformed");
            }
        }

        @Test
        @DisplayName("malformed SYNC returns ERROR without crashing the server")
        void malformedSyncDoesNotCrashServer() throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.init(ALICE_UUID);
                client.sendEnvelope(Opcode.SYNC, 1, new byte[] {1, 2, 3});
                Envelope response = client.readEnvelope();
                Assertions.assertNotNull(response);
                try {
                    Assertions.assertEquals(Opcode.ERROR, response.getHeader().opcode());
                    Assertions.assertEquals(
                            "ERROR Invalid SYNC format",
                            protocol.BufferUtil.readString(response.getBody()));
                } finally {
                    response.release();
                }
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("unauthenticated SYNC returns session error")
        void unauthenticatedSyncIsRejected() throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendSync(new UUID(0L, 0L));
                Envelope response = client.readEnvelope();
                Assertions.assertNotNull(response);
                try {
                    Assertions.assertEquals(Opcode.ERROR, response.getHeader().opcode());
                    Assertions.assertEquals(
                            "ERROR Session not initialized",
                            protocol.BufferUtil.readString(response.getBody()));
                } finally {
                    response.release();
                }
            }
            assertServerAcceptsConnection();
        }

        @Test
        @DisplayName("repeated malformed connections remain isolated")
        void repeatedMalformedConnectionsRemainIsolated() throws Exception {
            for (int i = 0; i < 50; i++) {
                try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                    client.sendRawFrame(
                            frame(
                                    (byte) (i & 0xFF),
                                    (byte) ((i * 3) & 0xFF),
                                    (byte) (0x09 + (i % 247)),
                                    (byte) i,
                                    i,
                                    new byte[Math.min(i, 64)]));
                    client.awaitClose(Duration.ofSeconds(1));
                }
            }
            assertServerAcceptsConnection();
        }

        private void pipeline(MessageTestClient client, int count, String prefix) {
            for (int i = 0; i < count; i++) {
                client.writeSendMessage(
                        CONVERSATION_ID, Instant.now().plusNanos(i).toString(), prefix + i);
            }
            client.flush();
        }

        private Set<UUID> readAckIds(MessageTestClient client, int expected, Duration timeout) {
            Set<UUID> ids = new HashSet<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (ids.size() < expected && System.nanoTime() < deadline) {
                UUID id = client.readAck();
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }

        private void assertServerAcceptsConnection() throws Exception {
            try (MessageTestClient client = connect()) {
                Assertions.assertEquals("SUCCESS", client.init(ALICE_UUID));
            }
        }

        private void assertMalformedFrameCloses(byte[] bytes) throws Exception {
            try (RawProtocolClient client = new RawProtocolClient(getServer().getPort())) {
                client.sendRawFrame(bytes);
                client.awaitClose(Duration.ofSeconds(5));
            }
            assertServerAcceptsConnection();
        }

        private static byte[] randomGarbage(int size) {
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) {
                bytes[i] = (byte) ((i * 31 + 17) & 0xFF);
            }
            return bytes;
        }

        private static byte[] header(
                byte magic, byte version, byte opcode, byte flags, int streamId, int bodyLength) {
            return frame(magic, version, opcode, flags, streamId, new byte[0], bodyLength);
        }

        private static byte[] frame(
                byte magic, byte version, byte opcode, byte flags, int streamId, byte[] body) {
            return frame(magic, version, opcode, flags, streamId, body, body.length);
        }

        private static byte[] frame(
                byte magic,
                byte version,
                byte opcode,
                byte flags,
                int streamId,
                byte[] body,
                int declaredLength) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(12 + body.length);
            try {
                DataOutputStream data = new DataOutputStream(output);
                data.writeByte(magic);
                data.writeByte(version);
                data.writeByte(opcode);
                data.writeByte(flags);
                data.writeInt(streamId);
                data.writeInt(declaredLength);
                data.write(body);
                data.flush();
                return output.toByteArray();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private static byte[] concat(byte[] first, byte[] second) {
            byte[] result = new byte[first.length + second.length];
            System.arraycopy(first, 0, result, 0, first.length);
            System.arraycopy(second, 0, result, first.length, second.length);
            return result;
        }

        private final class RawProtocolClient implements AutoCloseable {
            private final Socket socket;
            private final InputStream input;
            private final OutputStream output;
            private int streamId;

            private RawProtocolClient(int port) throws IOException {
                socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(3000);
                input = socket.getInputStream();
                output = socket.getOutputStream();
            }

            private void init(UUID userId) throws Exception {
                String token = createToken(userId, TestKeyManager.getKeyPair().getPrivate());
                ByteBuf body = Unpooled.buffer();
                BufferUtil.writeLongString(token, body);
                byte[] bytes = new byte[body.readableBytes()];
                body.readBytes(bytes);
                body.release();
                sendEnvelope(Opcode.INIT, ++streamId, bytes);
                Envelope response = readEnvelope();
                Assertions.assertNotNull(response);
                try {
                    Assertions.assertEquals(Opcode.READY, response.getHeader().opcode());
                } finally {
                    response.release();
                }
            }

            private void sendSync(UUID cursor) throws IOException {
                ByteBuf body = Unpooled.buffer(16);
                BufferUtil.writeUUID(cursor, body);
                byte[] bytes = new byte[body.readableBytes()];
                body.readBytes(bytes);
                body.release();
                sendEnvelope(Opcode.SYNC, ++streamId, bytes);
            }

            private void sendEnvelope(Opcode opcode, int streamId, byte[] body) throws IOException {
                sendRawFrame(
                        frame(
                                Envelope.MAGIC,
                                Envelope.VERSION,
                                opcode.getCode(),
                                (byte) 0,
                                streamId,
                                body));
            }

            private void sendRawFrame(byte[] bytes) throws IOException {
                output.write(bytes);
                output.flush();
            }

            private Envelope readEnvelope() throws IOException {
                byte[] header = readExactly(12);
                if (header == null) {
                    return null;
                }
                byte[] body = readExactly(readInt(header, 8));
                if (body == null) {
                    return null;
                }
                ByteBuf buffer = Unpooled.buffer(12 + body.length);
                buffer.writeBytes(header);
                buffer.writeBytes(body);
                io.netty.channel.embedded.EmbeddedChannel decoder =
                        new io.netty.channel.embedded.EmbeddedChannel(
                                new protocol.EnvelopeDecoder(MAX_FRAME_LENGTH));
                try {
                    decoder.writeInbound(buffer);
                    return decoder.readInbound();
                } finally {
                    decoder.finishAndReleaseAll();
                }
            }

            private Event readEvent(Duration timeout) throws IOException {
                int oldTimeout = socket.getSoTimeout();
                socket.setSoTimeout((int) timeout.toMillis());

                try {
                    while (true) {
                        Envelope envelope;

                        try {
                            envelope = readEnvelope();
                        } catch (SocketTimeoutException e) {
                            return null;
                        }

                        if (envelope == null) {
                            return null;
                        }

                        try {
                            if (envelope.getHeader().opcode() == Opcode.EVENT) {
                                return BinaryMessages.decodeEvent(envelope.getBody());
                            }
                        } finally {
                            envelope.release();
                        }
                    }
                } finally {
                    socket.setSoTimeout(oldTimeout);
                }
            }

            private List<Event> readEvents(int expected, Duration timeout) throws IOException {
                List<Event> events = new ArrayList<>();
                long deadline = System.nanoTime() + timeout.toNanos();

                while (events.size() < expected && System.nanoTime() < deadline) {
                    long remaining = deadline - System.nanoTime();

                    if (remaining <= 0) {
                        break;
                    }

                    Event event = readEvent(Duration.ofNanos(remaining));

                    if (event != null) {
                        events.add(event);
                    }
                }

                return events;
            }

            private void awaitClose(Duration timeout) throws IOException {
                long deadline = System.nanoTime() + timeout.toNanos();
                while (System.nanoTime() < deadline) {
                    try {
                        int value = input.read();
                        if (value == -1) {
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (SocketException e) {
                        return;
                    }
                }
                Assertions.fail("Connection did not close within " + timeout);
            }

            private void closeAbruptly() throws IOException {
                socket.setSoLinger(true, 0);
                socket.close();
            }

            private byte[] readExactly(int length) throws IOException {
                if (length < 0 || length > MAX_FRAME_LENGTH) {
                    throw new IOException("Invalid frame length: " + length);
                }
                byte[] bytes = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int read = input.read(bytes, offset, length - offset);
                    if (read == -1) {
                        if (offset == 0) {
                            return null;
                        }
                        throw new EOFException("Unexpected EOF");
                    }
                    offset += read;
                }
                return bytes;
            }

            private int readInt(byte[] bytes, int offset) {
                return ((bytes[offset] & 0xFF) << 24)
                        | ((bytes[offset + 1] & 0xFF) << 16)
                        | ((bytes[offset + 2] & 0xFF) << 8)
                        | (bytes[offset + 3] & 0xFF);
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        }
    }

    private static String createToken(UUID userId, PrivateKey privateKey) throws Exception {
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8));

        String payload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));

        String contentToSign = header + "." + payload;

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(contentToSign.getBytes(StandardCharsets.UTF_8));

        String signatureBase64 =
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return contentToSign + "." + signatureBase64;
    }
}
