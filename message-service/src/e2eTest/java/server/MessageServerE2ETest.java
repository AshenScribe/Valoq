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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MessageServerE2ETest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Core Routing & Cassandra Persistence")
    class CoreRoutingTests {

        @Test
        @DisplayName("End-to-end messaging routes between online clients and persists to Cassandra")
        void endToEndMessagingAndPersistenceFlow() throws Exception {
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                Assertions.assertEquals("SUCCESS", alice.init("alice"));
                Assertions.assertEquals("SUCCESS", bob.init("bob"));

                alice.sendTo("bob", "SGVsbG8=");
                Assertions.assertEquals("FROM alice SGVsbG8=", bob.readLine());

                bob.sendTo("alice", "V29ybGQ=");
                Assertions.assertEquals("FROM bob V29ybGQ=", alice.readLine());

                String query = "SELECT sender_id, recipient_id, payload FROM valoq_messages.messages;";
                long deadline = System.currentTimeMillis() + 3000;
                int rowCount = 0;
                while (System.currentTimeMillis() < deadline && rowCount < 2) {
                    rowCount = session.execute(query).all().size();
                    if (rowCount < 2) Thread.sleep(50);
                }
                Assertions.assertEquals(2, rowCount, "Both messages must be persisted in Cassandra");
            }
        }

        @Test
        @DisplayName("Sending to an offline recipient persists to Cassandra and returns error to sender")
        void sendToOfflineRecipientPersistsMessage() throws Exception {
            try (MessageTestClient alice = connect()) {
                Assertions.assertEquals("SUCCESS", alice.init("alice"));

                alice.sendTo("offline_user", "SGVsbG8=");
                Assertions.assertEquals("ERROR Recipient not connected", alice.readLine());

                Row row = awaitRow(
                        "SELECT sender_id, recipient_id, payload FROM valoq_messages.messages WHERE recipient_id = 'offline_user' ALLOW FILTERING;");
                Assertions.assertNotNull(row, "Message to offline recipient must be saved in Cassandra");
                Assertions.assertEquals("alice", row.getString("sender_id"));
                Assertions.assertEquals("SGVsbG8=", row.getString("payload"));
            }
        }

        @Test
        @DisplayName("Messages between 3 distinct users must only route to the intended recipient")
        void multiUserRoutingIsolation() throws Exception {
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect();
                    MessageTestClient charlie = connect()) {

                alice.init("alice");
                bob.init("bob");
                charlie.init("charlie");
                alice.sendTo("bob", "SGVsbG8gQm9i");

                Assertions.assertEquals("FROM alice SGVsbG8gQm9i", bob.readLine());
                Assertions.assertNull(
                        charlie.readLine(Duration.ofMillis(200)), "Charlie should not receive message meant for Bob");
            }
        }

        @Test
        @DisplayName("Client sending message to itself should receive it")
        void selfMessagingFlow() throws Exception {
            try (MessageTestClient alice = connect()) {
                alice.init("alice");
                alice.sendTo("alice", "U2VsZi1tZXNzYWdl");

                Assertions.assertEquals("FROM alice U2VsZi1tZXNzYWdl", alice.readLine());
            }
        }
    }

    @Nested
    @DisplayName("Connection Lifecycle & Disconnects")
    class ConnectionLifecycleTests {

        @Test
        @DisplayName("When recipient disconnects, server must clean up registry and return error on subsequent sends")
        void disconnectedRecipientBecomesUnreachable() throws Exception {
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init("alice");
                bob.init("bob");
                bob.close();
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline
                        && server.getConnectionTracker().get("bob") != null) {
                    Thread.sleep(20);
                }

                alice.sendTo("bob", "WW91IHRoZXJlPw==");
                Assertions.assertEquals("ERROR Recipient not connected", alice.readLine());
            }
        }

        @Test
        @DisplayName("Logging in with the same userId from a new connection overrides the old session")
        void duplicateUserUsesLatestConnection() throws Exception {
            try (MessageTestClient firstAlice = connect();
                    MessageTestClient secondAlice = connect();
                    MessageTestClient bob = connect()) {

                firstAlice.init("alice");
                bob.init("bob");
                secondAlice.init("alice");
                bob.sendTo("alice", "SGVsbG8=");

                Assertions.assertEquals("FROM bob SGVsbG8=", secondAlice.readLine());
                Assertions.assertNull(
                        firstAlice.readLine(Duration.ofMillis(200)), "Old connection should not receive the message");
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
        @DisplayName("Malformed SEND returns ERROR format and keeps connection open for subsequent messages")
        void malformedSendReturnsErrorAndKeepsConnectionAlive() throws Exception {
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init("alice");
                bob.init("bob");
                alice.send("SEND bob");
                Assertions.assertEquals("ERROR Invalid SEND format", alice.readLine());
                alice.sendTo("bob", "U3RpbGwgYWxpdmU=");
                Assertions.assertEquals("FROM alice U3RpbGwgYWxpdmU=", bob.readLine());
            }
        }

        @Test
        @DisplayName("Recipient ID exceeding 64 characters should be rejected")
        void recipientIdExceedingMaxCapacityRejected() throws Exception {
            try (MessageTestClient alice = connect()) {
                alice.init("alice");

                String oversizedRecipient = "a".repeat(65);
                alice.sendTo(oversizedRecipient, "cGF5bG9hZA==");

                Assertions.assertEquals("ERROR Invalid SEND format", alice.readLine());
            }
        }
    }

    @Nested
    @DisplayName("TCP Framing & Ordering")
    class TcpFramingTests {

        @Test
        @DisplayName("Rapid burst of messages must arrive in sequential chronological order")
        void rapidBurstMessageOrdering() throws Exception {
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                alice.init("alice");
                bob.init("bob");

                int burstCount = 5;
                for (int i = 0; i < burstCount; i++) {
                    alice.sendTo("bob", "msg_" + i);
                }

                for (int i = 0; i < burstCount; i++) {
                    Assertions.assertEquals("FROM alice msg_" + i, bob.readLine());
                }
            }
        }

        @Test
        @DisplayName("Pipelined TCP write (multiple frames in one packet) must be processed independently")
        void pipelinedCommandsInSinglePacket() throws Exception {
            try (MessageTestClient alice = connect();
                    MessageTestClient bob = connect()) {

                bob.init("bob");
                alice.send("INIT alice\nSEND bob UGlwZWxpbmVk\n");

                Assertions.assertEquals("SUCCESS", alice.readLine());
                Assertions.assertEquals("FROM alice UGlwZWxpbmVk", bob.readLine());
            }
        }
    }
}
