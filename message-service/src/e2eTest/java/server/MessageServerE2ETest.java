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
import database.BucketUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import protocol.Envelope;
import protocol.Opcode;
import server.model.Event;

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

    private static void assertMessageEvent(
            Event event, UUID conversationId, UUID senderId, String payload) {
        Assertions.assertNotNull(event);
        Assertions.assertEquals(conversationId, event.conversationId());
        Assertions.assertEquals(senderId, event.senderId());
        Assertions.assertEquals("MESSAGE_CREATED", event.eventType().name());
        Assertions.assertEquals(payload, event.payload());
    }
}
