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
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("UserEventRepository E2E Integration Tests")
class UserEventRepositoryE2ETest extends BaseIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5L;
    private UserEventRepository userEventRepository;

    @BeforeEach
    void setUp() {
        userEventRepository = new UserEventRepository(getSession());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("Single Persistence & Retrieval")
    class SingleUserEventTests {

        @Test
        @DisplayName("successfully saves and retrieves a user event record")
        void saveAndGetUserEvent() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            UUID eventId = Uuids.timeBased();
            String timeBucket = "2026-09";

            AsyncResultSet insertRs =
                    await(
                            userEventRepository
                                    .saveUserEvent(userId, timeBucket, eventId, conversationId)
                                    .toCompletableFuture());

            Assertions.assertTrue(insertRs.wasApplied(), "Insert must be applied successfully");

            UUID cursor = Uuids.startOf(0);
            AsyncResultSet selectRs =
                    await(
                            userEventRepository
                                    .getUserEvents(userId, cursor, timeBucket)
                                    .toCompletableFuture());

            List<Row> rows =
                    StreamSupport.stream(selectRs.currentPage().spliterator(), false).toList();
            Assertions.assertEquals(1, rows.size());

            Row row = rows.get(0);
            Assertions.assertEquals(eventId, row.getUuid("event_id"));
            Assertions.assertEquals(conversationId, row.getUuid("conversation_id"));
        }
    }

    @Nested
    @DisplayName("Cursor Pagination & Filtering")
    class PaginationTests {

        @Test
        @DisplayName("getUserEvents filters out events older than or equal to the supplied cursor")
        void cursorFilteringExcludesPastEvents() throws Exception {
            UUID userId = UUID.randomUUID();
            String timeBucket = "2026-09";
            UUID conversationId = UUID.randomUUID();

            UUID event1 = Uuids.timeBased();
            Thread.sleep(5);
            UUID event2 = Uuids.timeBased();
            Thread.sleep(5);
            UUID event3 = Uuids.timeBased();

            userEventRepository
                    .saveUserEvent(userId, timeBucket, event1, conversationId)
                    .toCompletableFuture()
                    .join();
            userEventRepository
                    .saveUserEvent(userId, timeBucket, event2, conversationId)
                    .toCompletableFuture()
                    .join();
            userEventRepository
                    .saveUserEvent(userId, timeBucket, event3, conversationId)
                    .toCompletableFuture()
                    .join();

            AsyncResultSet rs =
                    await(
                            userEventRepository
                                    .getUserEvents(userId, event2, timeBucket)
                                    .toCompletableFuture());

            List<Row> rows = StreamSupport.stream(rs.currentPage().spliterator(), false).toList();
            Assertions.assertEquals(
                    1, rows.size(), "Only events strictly newer than cursor should return");
            Assertions.assertEquals(event3, rows.get(0).getUuid("event_id"));
        }

        @Test
        @DisplayName("returns empty results when no events exist after the given cursor")
        void emptyWhenCursorIsLatest() throws Exception {
            UUID userId = UUID.randomUUID();
            String timeBucket = "2026-09";
            UUID eventId = Uuids.timeBased();

            userEventRepository
                    .saveUserEvent(userId, timeBucket, eventId, UUID.randomUUID())
                    .toCompletableFuture()
                    .join();

            AsyncResultSet rs =
                    await(
                            userEventRepository
                                    .getUserEvents(userId, eventId, timeBucket)
                                    .toCompletableFuture());

            List<Row> rows = StreamSupport.stream(rs.currentPage().spliterator(), false).toList();
            Assertions.assertTrue(rows.isEmpty());
        }
    }

    @Nested
    @DisplayName("Partition Isolation")
    class PartitionTests {

        @Test
        @DisplayName("different users and time buckets remain strictly isolated")
        void distinctUsersAndBucketsAreIsolated() throws Exception {
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            UUID eventA = Uuids.timeBased();
            UUID eventB = Uuids.timeBased();
            UUID conversationId = UUID.randomUUID();

            userEventRepository
                    .saveUserEvent(userA, "2026-08", eventA, conversationId)
                    .toCompletableFuture()
                    .join();
            userEventRepository
                    .saveUserEvent(userB, "2026-09", eventB, conversationId)
                    .toCompletableFuture()
                    .join();

            AsyncResultSet rsA =
                    await(
                            userEventRepository
                                    .getUserEvents(userA, Uuids.startOf(0), "2026-08")
                                    .toCompletableFuture());

            List<Row> rowsA = StreamSupport.stream(rsA.currentPage().spliterator(), false).toList();
            Assertions.assertEquals(1, rowsA.size());
            Assertions.assertEquals(eventA, rowsA.get(0).getUuid("event_id"));

            AsyncResultSet rsB =
                    await(
                            userEventRepository
                                    .getUserEvents(userB, Uuids.startOf(0), "2026-09")
                                    .toCompletableFuture());

            List<Row> rowsB = StreamSupport.stream(rsB.currentPage().spliterator(), false).toList();
            Assertions.assertEquals(1, rowsB.size());
            Assertions.assertEquals(eventB, rowsB.get(0).getUuid("event_id"));

            AsyncResultSet rsEmpty =
                    await(
                            userEventRepository
                                    .getUserEvents(userA, Uuids.startOf(0), "2026-09")
                                    .toCompletableFuture());
            List<Row> rowsEmpty =
                    StreamSupport.stream(rsEmpty.currentPage().spliterator(), false).toList();
            Assertions.assertTrue(rowsEmpty.isEmpty());
        }
    }

    @Nested
    @DisplayName("Concurrency & Load Handling")
    class ConcurrencyTests {

        @ParameterizedTest(name = "concurrentInserts={0}")
        @ValueSource(ints = {1, 5, 25})
        @DisplayName("handles concurrent asynchronous user event writes without data loss")
        void concurrentSaves(int count) throws Exception {
            UUID userId = UUID.randomUUID();
            String timeBucket = "2026-09";
            List<CompletableFuture<AsyncResultSet>> futures = new ArrayList<>();
            List<UUID> expectedIds = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                UUID eventId = Uuids.timeBased();
                expectedIds.add(eventId);
                futures.add(
                        userEventRepository
                                .saveUserEvent(userId, timeBucket, eventId, UUID.randomUUID())
                                .toCompletableFuture());
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            AsyncResultSet rs =
                    await(
                            userEventRepository
                                    .getUserEvents(userId, Uuids.startOf(0), timeBucket)
                                    .toCompletableFuture());

            List<Row> rows = StreamSupport.stream(rs.currentPage().spliterator(), false).toList();
            Assertions.assertEquals(count, rows.size());
        }
    }
}
