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
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventRepositoryTest extends BaseIntegrationTest {

    private EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository = new EventRepository(getSession());
    }

    @Nested
    @DisplayName("Single Event Persistence")
    class SingleEventTests {

        @Test
        @DisplayName("Should successfully insert a generic event")
        void saveEventSuccessfullyPersistsEvent() throws Exception {

            UUID conversationId = UUID.randomUUID();
            String timeBucket = "2026-09";
            int hashBucket = 0;
            UUID eventId = Uuids.timeBased();
            UUID actorId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();

            AsyncResultSet result =
                    eventRepository
                            .saveEvent(
                                    conversationId,
                                    timeBucket,
                                    hashBucket,
                                    eventId,
                                    "MESSAGE_CREATED",
                                    actorId,
                                    entityId,
                                    "SGVsbG8gQm9iIQ==")
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);

            Assertions.assertTrue(result.wasApplied(), "Insert statement must be applied");

            String cql =
                    """
                    SELECT conversation_id,
                           time_bucket,
                           hash_bucket,
                           event_id,
                           event_type,
                           actor_id,
                           entity_id,
                           payload
                    FROM valoq_messages.events
                    WHERE conversation_id = ?
                      AND time_bucket = ?
                      AND hash_bucket = ?
                    """;

            ResultSet rs =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(cql)
                                            .bind(conversationId, timeBucket, hashBucket));

            Row row = rs.one();

            Assertions.assertNotNull(row, "Event must be found in Cassandra");

            Assertions.assertEquals(conversationId, row.getUuid("conversation_id"));

            Assertions.assertEquals(timeBucket, row.getString("time_bucket"));

            Assertions.assertEquals(hashBucket, row.getInt("hash_bucket"));

            Assertions.assertEquals(eventId, row.getUuid("event_id"));

            Assertions.assertEquals("MESSAGE_CREATED", row.getString("event_type"));

            Assertions.assertEquals(actorId, row.getUuid("actor_id"));

            Assertions.assertEquals(entityId, row.getUuid("entity_id"));

            Assertions.assertEquals("SGVsbG8gQm9iIQ==", row.getString("payload"));
        }

        @Test
        @DisplayName("Should preserve large event payloads without truncation")
        void saveEventLargePayloadStoredAccurately() throws Exception {

            UUID conversationId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();

            String largePayload = "A".repeat(64 * 1024);

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            actorId,
                            entityId,
                            largePayload)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet rs =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 0));

            Row row = rs.one();

            Assertions.assertNotNull(row);
            Assertions.assertEquals(largePayload, row.getString("payload"));
        }
    }

    @Nested
    @DisplayName("Event Partitioning")
    class PartitioningTests {

        @Test
        @DisplayName(
                "Events belonging to the same conversation and bucket must share the partition")
        void eventsShareSamePartition() throws Exception {

            UUID conversationId = UUID.randomUUID();

            UUID event1 = Uuids.timeBased();
            UUID actor1 = UUID.randomUUID();
            UUID entity1 = UUID.randomUUID();

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            event1,
                            "MESSAGE_CREATED",
                            actor1,
                            entity1,
                            "msg_1")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            Thread.sleep(10);

            UUID event2 = Uuids.timeBased();
            UUID actor2 = UUID.randomUUID();
            UUID entity2 = UUID.randomUUID();

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            event2,
                            "MESSAGE_CREATED",
                            actor2,
                            entity2,
                            "msg_2")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet rs =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT event_id,
                                                           event_type,
                                                           actor_id,
                                                           entity_id,
                                                           payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 0));

            List<Row> rows = rs.all();
            rows.sort(
                    (left, right) -> left.getUuid("event_id").compareTo(right.getUuid("event_id")));

            Assertions.assertEquals(
                    2, rows.size(), "Both events must exist in the shared partition");

            Assertions.assertEquals(actor1, rows.get(0).getUuid("actor_id"));

            Assertions.assertEquals("msg_1", rows.get(0).getString("payload"));

            Assertions.assertEquals(actor2, rows.get(1).getUuid("actor_id"));

            Assertions.assertEquals("msg_2", rows.get(1).getString("payload"));
        }

        @Test
        @DisplayName("Different conversations must be stored in completely isolated partitions")
        void distinctConversationsAreIsolated() throws Exception {

            UUID conversationA = UUID.randomUUID();
            UUID conversationB = UUID.randomUUID();

            eventRepository
                    .saveEvent(
                            conversationA,
                            "2026-09",
                            0,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "for_a")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            eventRepository
                    .saveEvent(
                            conversationB,
                            "2026-09",
                            0,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "for_b")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet rowsA =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationA, "2026-09", 0));

            ResultSet rowsB =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationB, "2026-09", 0));

            Assertions.assertEquals("for_a", rowsA.one().getString("payload"));

            Assertions.assertEquals("for_b", rowsB.one().getString("payload"));
        }

        @Test
        @DisplayName("Different time buckets must be stored in different Cassandra partitions")
        void differentTimeBucketsAreIsolated() throws Exception {

            UUID conversationId = UUID.randomUUID();

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-08",
                            0,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "august")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "september")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet august =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-08", 0));

            ResultSet september =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 0));

            Assertions.assertEquals("august", august.one().getString("payload"));

            Assertions.assertEquals("september", september.one().getString("payload"));
        }

        @Test
        @DisplayName("Different hash buckets must be stored in different partitions")
        void differentHashBucketsAreIsolated() throws Exception {

            UUID conversationId = UUID.randomUUID();

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "bucket_0")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            1,
                            Uuids.timeBased(),
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "bucket_1")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet bucket0 =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 0));

            ResultSet bucket1 =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 1));

            Assertions.assertEquals("bucket_0", bucket0.one().getString("payload"));

            Assertions.assertEquals("bucket_1", bucket1.one().getString("payload"));
        }
    }

    @Nested
    @DisplayName("Event Ordering")
    class OrderingTests {

        @Test
        @DisplayName(
                "Events in the same partition must be returned in event_id chronological order")
        void eventsMaintainChronologicalOrder() throws Exception {

            UUID conversationId = UUID.randomUUID();

            UUID first = Uuids.timeBased();

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            first,
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "first")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            Thread.sleep(10);

            UUID second = Uuids.timeBased();

            eventRepository
                    .saveEvent(
                            conversationId,
                            "2026-09",
                            0,
                            second,
                            "MESSAGE_CREATED",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "second")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            ResultSet rs =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT event_id, payload
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 0));

            List<Row> rows = rs.all();
            rows.sort(
                    (left, right) -> left.getUuid("event_id").compareTo(right.getUuid("event_id")));

            Assertions.assertEquals(2, rows.size());

            Assertions.assertEquals(first, rows.get(0).getUuid("event_id"));

            Assertions.assertEquals("first", rows.get(0).getString("payload"));

            Assertions.assertEquals(second, rows.get(1).getUuid("event_id"));

            Assertions.assertEquals("second", rows.get(1).getString("payload"));
        }
    }

    @Nested
    @DisplayName("Concurrency & Load Resilience")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should handle 30 simultaneous asynchronous event inserts")
        void saveEventConcurrentInsertsAllPersisted() {

            UUID conversationId = UUID.randomUUID();

            int eventCount = 30;

            List<CompletableFuture<AsyncResultSet>> futures = new ArrayList<>();

            for (int i = 0; i < eventCount; i++) {

                final int index = i;

                UUID eventId = Uuids.timeBased();

                CompletableFuture<AsyncResultSet> future =
                        eventRepository
                                .saveEvent(
                                        conversationId,
                                        "2026-09",
                                        0,
                                        eventId,
                                        "MESSAGE_CREATED",
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "payload_" + index)
                                .toCompletableFuture();

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            ResultSet rs =
                    getSession()
                            .execute(
                                    getSession()
                                            .prepare(
                                                    """
                                                    SELECT count(*)
                                                    FROM valoq_messages.events
                                                    WHERE conversation_id = ?
                                                      AND time_bucket = ?
                                                      AND hash_bucket = ?
                                                    """)
                                            .bind(conversationId, "2026-09", 0));

            Row countRow = rs.one();

            Assertions.assertNotNull(countRow);

            Assertions.assertEquals(
                    eventCount, countRow.getLong(0), "All events must be committed to Cassandra");
        }
    }
}
