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
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MessageServerE2ETest extends BaseIntegrationTest {

    private static final String CONVERSATION_ID = "conversation-e2e";

    private static final String CREATED_AT = "2026-09-30T17:30:00Z";

    @Nested
    @DisplayName("Core Routing & Cassandra Persistence")
    class CoreRoutingTests {

        @Test
        @DisplayName("End-to-end messaging routes between online clients and persists to Cassandra")
        void endToEndMessagingAndPersistenceFlow() throws Exception {

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                Assertions.assertEquals("SUCCESS", alice.init("alice"));

                Assertions.assertEquals("SUCCESS", bob.init("bob"));

                String aliceMessageId = UUID.randomUUID().toString();

                alice.sendMessage(CONVERSATION_ID, aliceMessageId, CREATED_AT, "SGVsbG8=");

                String bobEvent = bob.readLine();

                Assertions.assertNotNull(bobEvent);
                Assertions.assertTrue(bobEvent.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(bobEvent.contains(CONVERSATION_ID));
                Assertions.assertTrue(bobEvent.contains("alice"));
                Assertions.assertTrue(bobEvent.endsWith("SGVsbG8="));

                String aliceEvent = alice.readLine();

                Assertions.assertNotNull(aliceEvent);
                Assertions.assertTrue(aliceEvent.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(aliceEvent.contains(CONVERSATION_ID));
                Assertions.assertTrue(aliceEvent.contains("alice"));
                Assertions.assertTrue(aliceEvent.endsWith("SGVsbG8="));

                String bobMessageId = UUID.randomUUID().toString();

                bob.sendMessage(CONVERSATION_ID, bobMessageId, CREATED_AT, "V29ybGQ=");

                String aliceEvent2 = alice.readLine();

                Assertions.assertNotNull(aliceEvent2);
                Assertions.assertTrue(aliceEvent2.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(aliceEvent2.contains(CONVERSATION_ID));
                Assertions.assertTrue(aliceEvent2.contains("bob"));
                Assertions.assertTrue(aliceEvent2.endsWith("V29ybGQ="));

                String bobEvent2 = bob.readLine();

                Assertions.assertNotNull(bobEvent2);
                Assertions.assertTrue(bobEvent2.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(bobEvent2.contains("bob"));
                Assertions.assertTrue(bobEvent2.endsWith("V29ybGQ="));

                Row row =
                        awaitRow(
                                """
                                SELECT event_id, event_type, actor_id, payload
                                FROM valoq_messages.events
                                WHERE conversation_id = 'conversation-e2e'
                                  AND time_bucket = '2026-09'
                                  AND hash_bucket = 0
                                LIMIT 1
                                """);

                Assertions.assertNotNull(row, "Message must be persisted in Cassandra");

                Assertions.assertEquals("MESSAGE_CREATED", row.getString("event_type"));
            }
        }

        @Test
        @DisplayName(
                "Sending to an offline conversation member persists the event and does not fail")
        void sendToOfflineMemberPersistsEvent() throws Exception {

            addConversationMembers(CONVERSATION_ID, "alice", "offline_user");

            try (MessageTestClient alice = connect()) {

                Assertions.assertEquals("SUCCESS", alice.init("alice"));

                alice.sendMessage(
                        CONVERSATION_ID, UUID.randomUUID().toString(), CREATED_AT, "SGVsbG8=");

                /*
                 * Sender is online, so it receives its own event.
                 */
                String event = alice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(CONVERSATION_ID));
                Assertions.assertTrue(event.contains("alice"));
                Assertions.assertTrue(event.endsWith("SGVsbG8="));

                Row row =
                        awaitRow(
                                """
                                SELECT event_id, event_type, actor_id, payload
                                FROM valoq_messages.events
                                WHERE conversation_id = 'conversation-e2e'
                                  AND time_bucket = '2026-09'
                                  AND hash_bucket = 0
                                LIMIT 1
                                """);

                Assertions.assertNotNull(row, "Offline member message must be persisted");

                Assertions.assertEquals("MESSAGE_CREATED", row.getString("event_type"));

                Assertions.assertEquals("alice", row.getString("actor_id"));

                Assertions.assertEquals("SGVsbG8=", row.getString("payload"));
            }
        }

        @Test
        @DisplayName("Messages in a conversation are delivered only to its members")
        void multiUserRoutingIsolation() throws Exception {

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            addConversationMember("conversation-other", "charlie");

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect();
                    MessageTestClient charlie = connect()) {

                alice.init("alice");
                bob.init("bob");
                charlie.init("charlie");

                alice.sendMessage(
                        CONVERSATION_ID, UUID.randomUUID().toString(), CREATED_AT, "SGVsbG8gQm9i");

                String bobEvent = bob.readLine();

                Assertions.assertNotNull(bobEvent);
                Assertions.assertTrue(bobEvent.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(bobEvent.contains(CONVERSATION_ID));
                Assertions.assertTrue(bobEvent.contains("alice"));
                Assertions.assertTrue(bobEvent.endsWith("SGVsbG8gQm9i"));

                Assertions.assertNull(
                        charlie.readLine(Duration.ofMillis(200)),
                        "Charlie is not a member of the conversation");
            }
        }

        @Test
        @DisplayName("Client sending a message to its conversation receives the event")
        void selfMessagingFlow() throws Exception {

            addConversationMember(CONVERSATION_ID, "alice");

            try (MessageTestClient alice = connect()) {

                alice.init("alice");

                alice.sendMessage(
                        CONVERSATION_ID,
                        UUID.randomUUID().toString(),
                        CREATED_AT,
                        "U2VsZi1tZXNzYWdl");

                String event = alice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains(CONVERSATION_ID));
                Assertions.assertTrue(event.contains("alice"));
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

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init("alice");
                bob.init("bob");

                bob.close();

                long deadline = System.currentTimeMillis() + 2000;

                while (System.currentTimeMillis() < deadline
                        && getServer().getConnectionTracker().get("bob") != null) {

                    Thread.sleep(20);
                }

                alice.sendMessage(
                        CONVERSATION_ID,
                        UUID.randomUUID().toString(),
                        CREATED_AT,
                        "WW91IHRoZXJlPw==");

                String event = alice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains("alice"));
                Assertions.assertTrue(event.endsWith("WW91IHRoZXJlPw=="));
            }
        }

        @Test
        @DisplayName(
                "Logging in with the same userId from a new connection overrides the old connection")
        void duplicateUserUsesLatestConnection() throws Exception {

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            try (MessageTestClient firstAlice = connect();
                    MessageTestClient secondAlice = connect();
                    MessageTestClient bob = connect()) {

                firstAlice.init("alice");
                bob.init("bob");
                secondAlice.init("alice");

                bob.sendMessage(
                        CONVERSATION_ID, UUID.randomUUID().toString(), CREATED_AT, "SGVsbG8=");

                String event = secondAlice.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains("bob"));
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

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init("alice");
                bob.init("bob");

                alice.send("SEND bob");

                Assertions.assertEquals("ERROR Invalid SEND format", alice.readLine());

                alice.sendMessage(
                        CONVERSATION_ID,
                        UUID.randomUUID().toString(),
                        CREATED_AT,
                        "U3RpbGwgYWxpdmU=");

                String event = bob.readLine();

                Assertions.assertNotNull(event);
                Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));
                Assertions.assertTrue(event.contains("alice"));
                Assertions.assertTrue(event.endsWith("U3RpbGwgYWxpdmU="));
            }
        }

        @Test
        @DisplayName("Conversation ID exceeding 128 characters should be rejected")
        void conversationIdExceedingMaxCapacityRejected() throws Exception {

            try (MessageTestClient alice = connect()) {

                alice.init("alice");

                String oversizedConversationId = "a".repeat(129);

                alice.sendMessage(
                        oversizedConversationId,
                        UUID.randomUUID().toString(),
                        CREATED_AT,
                        "cGF5bG9hZA==");

                Assertions.assertEquals("ERROR Invalid SEND format", alice.readLine());
            }
        }
    }

    @Nested
    @DisplayName("TCP Framing & Ordering")
    class TcpFramingTests {

        @Test
        @DisplayName("Rapid burst of messages must arrive in event order")
        void rapidBurstMessageOrdering() throws Exception {

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init("alice");
                bob.init("bob");

                int burstCount = 5;

                for (int i = 0; i < burstCount; i++) {

                    alice.sendMessage(
                            CONVERSATION_ID, UUID.randomUUID().toString(), CREATED_AT, "bXNnX" + i);
                }

                for (int i = 0; i < burstCount; i++) {

                    String event = bob.readLine();

                    Assertions.assertNotNull(event);

                    Assertions.assertTrue(event.startsWith("EVENT MESSAGE_CREATED "));

                    Assertions.assertTrue(event.contains(CONVERSATION_ID));

                    Assertions.assertTrue(event.endsWith("bXNnX" + i));
                }
            }
        }

        @Test
        @DisplayName("Pipelined TCP writes must be processed independently")
        void pipelinedCommandsInSinglePacket() throws Exception {

            addConversationMembers(CONVERSATION_ID, "alice", "bob");

            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                bob.init("bob");

                String aliceToken = alice.createToken("alice");

                String clientMessageId = UUID.randomUUID().toString();

                String pipelined =
                        "INIT "
                                + aliceToken
                                + "\n"
                                + "SEND "
                                + CONVERSATION_ID
                                + " "
                                + clientMessageId
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
                Assertions.assertTrue(event.contains(CONVERSATION_ID));
                Assertions.assertTrue(event.contains("alice"));
                Assertions.assertTrue(event.endsWith("UGlwcGVsaW5lZA=="));
            }
        }
    }
}
