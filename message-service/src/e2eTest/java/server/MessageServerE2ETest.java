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

                String bobEvent = bob.readLine();

                Assertions.assertNotNull(bobEvent);
                Assertions.assertTrue(bobEvent.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(bobEvent.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(bobEvent.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(bobEvent.endsWith("SGVsbG8="));

                String aliceEvent = alice.readLine();

                Assertions.assertNotNull(aliceEvent);
                Assertions.assertTrue(aliceEvent.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(aliceEvent.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(aliceEvent.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(aliceEvent.endsWith("SGVsbG8="));

                bob.sendMessage(CONVERSATION_ID, CREATED_AT, "V29ybGQ=");

                String aliceEvent2 = alice.readLine();

                Assertions.assertNotNull(aliceEvent2);
                Assertions.assertTrue(aliceEvent2.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(aliceEvent2.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(aliceEvent2.contains(BOB_UUID.toString()));
                Assertions.assertTrue(aliceEvent2.endsWith("V29ybGQ="));

                String bobEvent2 = bob.readLine();

                Assertions.assertNotNull(bobEvent2);
                Assertions.assertTrue(bobEvent2.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(bobEvent2.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(bobEvent2.contains(BOB_UUID.toString()));
                Assertions.assertTrue(bobEvent2.endsWith("V29ybGQ="));

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

                /*
                 * Sender is online, so it receives its own event.
                 */
                String event = alice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(event.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(event.endsWith("SGVsbG8="));

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

                String bobEvent = bob.readLine();

                Assertions.assertNotNull(bobEvent);
                Assertions.assertTrue(bobEvent.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(bobEvent.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(bobEvent.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(bobEvent.endsWith("SGVsbG8gQm9i"));

                Assertions.assertNull(
                        charlie.readLine(Duration.ofMillis(200)),
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

                String event = alice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(event.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(event.endsWith("U2VsZi1tZXNzYWdl"));
            }
        }
    }

    @Nested
    @DisplayName("Connection Lifecycle & Disconnects")
    class ConnectionLifecycleTests {

        @Test
        @DisplayName(
                "When a conversation member disconnects, the remaining member still receives events")
        void disconnectedMemberDoesNotBreakRouting() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init(ALICE_UUID);
                bob.init(BOB_UUID);

                bob.close();

                long deadline = System.currentTimeMillis() + 2000;

                while (System.currentTimeMillis() < deadline
                        && getServer().getConnectionTracker().get(BOB_UUID.toString()) != null) {

                    Thread.sleep(20);
                }

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "WW91IHRoZXJlPw==");

                String event = alice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(event.endsWith("WW91IHRoZXJlPw=="));
            }
        }

        @Test
        @DisplayName(
                "Logging in with the same userId from a new connection overrides the old connection")
        void duplicateUserUsesLatestConnection() throws Exception {
            addConversationMembers(CONVERSATION_ID, ALICE_UUID, BOB_UUID);

            try (MessageTestClient firstAlice = connect();
                    MessageTestClient secondAlice = connect();
                    MessageTestClient bob = connect()) {

                firstAlice.init(ALICE_UUID);
                bob.init(BOB_UUID);
                secondAlice.init(ALICE_UUID);

                bob.sendMessage(CONVERSATION_ID, CREATED_AT, "SGVsbG8=");

                String event = secondAlice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(BOB_UUID.toString()));
                Assertions.assertTrue(event.endsWith("SGVsbG8="));

                Assertions.assertNull(
                        firstAlice.readLine(Duration.ofMillis(200)),
                        "Old connection should not receive the message");
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

                client.send("init bad_format");

                Assertions.assertEquals("INVALID", client.readLine());

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

                alice.send("SEND bob");

                Assertions.assertEquals("ERROR Invalid SEND format", alice.readLine());

                alice.sendMessage(CONVERSATION_ID, CREATED_AT, "U3RpbGwgYWxpdmU=");

                String event = bob.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(event.endsWith("U3RpbGwgYWxpdmU="));
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
                    String event = bob.readLine();

                    Assertions.assertNotNull(event);
                    Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                    Assertions.assertTrue(event.contains(CONVERSATION_ID.toString()));
                    Assertions.assertTrue(event.endsWith("bXNnX" + i));
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

                String aliceToken = alice.createToken(ALICE_UUID);

                String pipelined =
                        "INIT "
                                + aliceToken
                                + "\n"
                                + "SEND "
                                + CONVERSATION_ID
                                + " "
                                + CREATED_AT
                                + " "
                                + "UGlwcGVsaW5lZA=="
                                + "\n";

                alice.send(pipelined);

                Assertions.assertEquals("SUCCESS", alice.readLine());

                String event = bob.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(CONVERSATION_ID.toString()));
                Assertions.assertTrue(event.contains(ALICE_UUID.toString()));
                Assertions.assertTrue(event.endsWith("UGlwcGVsaW5lZA=="));
            }
        }
    }
}
