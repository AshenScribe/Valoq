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
package database;

import base.BaseIntegrationTest;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MessageRepositoryTest extends BaseIntegrationTest {

    private MessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        messageRepository = new MessageRepository(getSession());
    }

    @Nested
    @DisplayName("Single Message Persistence")
    class SingleMessageTests {

        @Test
        @DisplayName(
                "Should successfully insert message asynchronously and verify all columns in Cassandra")
        void saveMessageAsyncSuccessfullyPersistsMessage() throws Exception {
            String sender = "usr_alice";
            String recipient = "usr_bob";
            String payload = "SGVsbG8gQm9iIQ==";
            Instant beforeInsert = Instant.now().minusSeconds(1);

            AsyncResultSet asyncResult =
                    messageRepository
                            .saveMessageAsync(sender, recipient, payload)
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);

            Assertions.assertTrue(asyncResult.wasApplied(), "Insert statement must be applied");
            String cql =
                    "SELECT conversation_id, message_id, sender_id, recipient_id, payload, created_at "
                            + "FROM valoq_messages.messages WHERE conversation_id = ?;";
            ResultSet rs =
                    getSession().execute(getSession().prepare(cql).bind("usr_alice:usr_bob"));
            Row row = rs.one();

            Assertions.assertNotNull(row, "Row must be found in Cassandra");
            Assertions.assertEquals("usr_alice:usr_bob", row.getString("conversation_id"));
            Assertions.assertEquals(sender, row.getString("sender_id"));
            Assertions.assertEquals(recipient, row.getString("recipient_id"));
            Assertions.assertEquals(payload, row.getString("payload"));
            Assertions.assertNotNull(row.getUuid("message_id"));
            Instant createdAt = row.getInstant("created_at");
            Assertions.assertNotNull(createdAt);
            Assertions.assertTrue(
                    createdAt.isAfter(beforeInsert) || createdAt.equals(beforeInsert),
                    "Timestamp must be recorded accurately");
        }

        @Test
        @DisplayName("Should preserve large Base64 payloads without truncation")
        void saveMessageAsyncLargePayloadStoredAccurately() throws Exception {
            String largePayload = "A".repeat(64 * 1024);

            messageRepository
                    .saveMessageAsync("alice", "bob", largePayload)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet rs =
                    getSession()
                            .execute(
                                    "SELECT payload FROM valoq_messages.messages WHERE conversation_id = 'alice:bob';");
            Row row = rs.one();

            Assertions.assertNotNull(row);
            Assertions.assertEquals(largePayload, row.getString("payload"));
        }
    }

    @Nested
    @DisplayName("Conversation ID & Partitioning")
    class PartitioningTests {

        @Test
        @DisplayName(
                "Bidirectional messages (Alice->Bob and Bob->Alice) must share the same partition and maintain chronological clustering order")
        void saveMessageAsyncBidirectionalMessagesShareSamePartition() throws Exception {
            messageRepository
                    .saveMessageAsync("alice", "bob", "msg_1")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            Thread.sleep(10);
            messageRepository
                    .saveMessageAsync("bob", "alice", "msg_2")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            String cql =
                    "SELECT sender_id, recipient_id, payload FROM valoq_messages.messages WHERE conversation_id = ?;";
            ResultSet rs = getSession().execute(getSession().prepare(cql).bind("alice:bob"));
            List<Row> rows = rs.all();

            Assertions.assertEquals(
                    2, rows.size(), "Both messages must exist in the shared partition");
            Assertions.assertEquals("alice", rows.get(0).getString("sender_id"));
            Assertions.assertEquals("msg_1", rows.get(0).getString("payload"));

            Assertions.assertEquals("bob", rows.get(1).getString("sender_id"));
            Assertions.assertEquals("msg_2", rows.get(1).getString("payload"));
        }

        @Test
        @DisplayName("Different conversations must be stored in completely isolated partitions")
        void saveMessageAsyncDistinctConversationsAreIsolated() throws Exception {
            messageRepository
                    .saveMessageAsync("alice", "bob", "for_bob")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            messageRepository
                    .saveMessageAsync("alice", "charlie", "for_charlie")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            ResultSet rsBob =
                    getSession()
                            .execute(
                                    "SELECT payload FROM valoq_messages.messages WHERE conversation_id = 'alice:bob';");
            List<Row> bobRows = rsBob.all();
            Assertions.assertEquals(1, bobRows.size());
            Assertions.assertEquals("for_bob", bobRows.get(0).getString("payload"));
            ResultSet rsCharlie =
                    getSession()
                            .execute(
                                    "SELECT payload FROM valoq_messages.messages WHERE conversation_id = 'alice:charlie';");
            List<Row> charlieRows = rsCharlie.all();
            Assertions.assertEquals(1, charlieRows.size());
            Assertions.assertEquals("for_charlie", charlieRows.get(0).getString("payload"));
        }

        @ParameterizedTest
        @CsvSource({
            "alice, bob, alice:bob",
            "bob, alice, alice:bob",
            "user_10, user_2, user_10:user_2",
            "user_2, user_10, user_10:user_2",
            "same_user, same_user, same_user:same_user"
        })
        @DisplayName(
                "getConversationId should produce deterministic keys regardless of argument order")
        void getConversationIdIsDeterministic(
                String user1, String user2, String expectedConversationId) {
            Assertions.assertEquals(
                    expectedConversationId, MessageRepository.getConversationId(user1, user2));
        }
    }

    @Nested
    @DisplayName("Concurrency & Load Resilience")
    class ConcurrencyTests {

        @Test
        @DisplayName(
                "Should handle 30 simultaneous asynchronous inserts without dropped writes or race conditions")
        void saveMessageAsyncConcurrentInsertsAllPersisted() {
            int messageCount = 30;
            List<CompletableFuture<AsyncResultSet>> futures = new ArrayList<>();

            for (int i = 0; i < messageCount; i++) {
                final int idx = i;
                CompletableFuture<AsyncResultSet> future =
                        messageRepository
                                .saveMessageAsync("alice", "bob", "payload_" + idx)
                                .toCompletableFuture();
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            ResultSet rs =
                    getSession()
                            .execute(
                                    "SELECT count(*) FROM valoq_messages.messages WHERE conversation_id = 'alice:bob';");
            Row countRow = rs.one();
            Assertions.assertNotNull(countRow);
            Assertions.assertEquals(
                    messageCount,
                    countRow.getLong(0),
                    "All 30 messages must be committed to Cassandra");
        }
    }
}
