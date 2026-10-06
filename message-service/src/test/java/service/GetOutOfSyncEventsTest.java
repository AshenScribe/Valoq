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
package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import database.BucketUtils;
import database.EventRepository;
import database.UserEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import server.model.Event;
import server.model.EventType;

@DisplayName("GetOutOfSyncEvents Tests")
class GetOutOfSyncEventsTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final long FUTURE_TIMEOUT_SECONDS = 5L;

    private UserEventRepository userEventRepository;
    private EventRepository eventRepository;
    private UUID clientCursor;

    @BeforeEach
    void setUp() {
        userEventRepository = mock(UserEventRepository.class);
        eventRepository = mock(EventRepository.class);
        clientCursor = Uuids.timeBased();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static UUID timeBased() {
        return Uuids.timeBased();
    }

    private static Row userEventRow(UUID eventId, UUID conversationId) {
        Row row = mock(Row.class);
        when(row.getUuid("event_id")).thenReturn(eventId);
        when(row.getUuid("conversation_id")).thenReturn(conversationId);
        return row;
    }

    private void stubUserEvents(List<Row> rows) {
        AsyncResultSet rs = mock(AsyncResultSet.class);

        when(rs.currentPage()).thenReturn(rows);

        when(userEventRepository.getUserEvents(any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(rs));
    }

    private void stubEventResponses(Optional<Event>... responses) {
        List<Optional<Event>> list = List.of(responses);
        List<CompletionStage<Optional<Event>>> stages = new ArrayList<>(list.size());

        for (Optional<Event> response : list) {
            stages.add(CompletableFuture.completedFuture(response));
        }

        final int[] index = {0};

        when(eventRepository.getEvent(any(UUID.class), anyString(), anyInt(), any(UUID.class)))
                .thenAnswer(
                        invocation -> {
                            int current = index[0]++;

                            return current < stages.size()
                                    ? stages.get(current)
                                    : CompletableFuture.completedFuture(Optional.empty());
                        });
    }

    private static Event sampleEvent(UUID eventId, UUID conversationId) {
        return new Event(
                UUID.randomUUID(),
                conversationId,
                eventId,
                EventType.MESSAGE_CREATED,
                "payload",
                Instant.ofEpochMilli(Uuids.unixTimestamp(eventId)));
    }

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

        @Test
        @DisplayName("returns events for all user-event rows")
        void returnsAllEvents() throws Exception {
            UUID e1 = timeBased();
            UUID e2 = timeBased();
            UUID e3 = timeBased();

            UUID c1 = UUID.randomUUID();
            UUID c2 = UUID.randomUUID();
            UUID c3 = UUID.randomUUID();

            stubUserEvents(
                    List.of(userEventRow(e1, c1), userEventRow(e2, c2), userEventRow(e3, c3)));

            Event ev1 = sampleEvent(e1, c1);
            Event ev2 = sampleEvent(e2, c2);
            Event ev3 = sampleEvent(e3, c3);

            stubEventResponses(Optional.of(ev1), Optional.of(ev2), Optional.of(ev3));

            GetOutOfSyncEvents service =
                    new GetOutOfSyncEvents(
                            USER_ID, clientCursor.toString(), userEventRepository, eventRepository);

            List<Event> result = await(service.serve());

            assertEquals(List.of(ev1, ev2, ev3), result);
        }

        @Test
        @DisplayName("returns empty list when user has no events")
        void emptyWhenNoUserEvents() throws Exception {
            stubUserEvents(Collections.emptyList());

            GetOutOfSyncEvents service =
                    new GetOutOfSyncEvents(
                            USER_ID, clientCursor.toString(), userEventRepository, eventRepository);

            List<Event> result = await(service.serve());

            assertTrue(result.isEmpty());

            verify(eventRepository, never()).getEvent(any(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("preserves order of user-event rows in result")
        void preservesOrder() throws Exception {
            UUID e1 = timeBased();
            UUID e2 = timeBased();
            UUID e3 = timeBased();

            UUID c1 = UUID.randomUUID();
            UUID c2 = UUID.randomUUID();
            UUID c3 = UUID.randomUUID();

            stubUserEvents(
                    List.of(userEventRow(e1, c1), userEventRow(e2, c2), userEventRow(e3, c3)));

            Event ev1 = sampleEvent(e1, c1);
            Event ev2 = sampleEvent(e2, c2);
            Event ev3 = sampleEvent(e3, c3);

            stubEventResponses(Optional.of(ev1), Optional.of(ev2), Optional.of(ev3));

            List<Event> result =
                    await(
                            new GetOutOfSyncEvents(
                                            USER_ID,
                                            clientCursor.toString(),
                                            userEventRepository,
                                            eventRepository)
                                    .serve());

            assertEquals(List.of(ev1, ev2, ev3), result);
        }
    }

    @Nested
    @DisplayName("Missing Events")
    class MissingEvents {

        @Test
        @DisplayName("filters out empty Optional results")
        void filtersEmptyOptionals() throws Exception {
            UUID e1 = timeBased();
            UUID e2 = timeBased();
            UUID e3 = timeBased();

            stubUserEvents(
                    List.of(
                            userEventRow(e1, UUID.randomUUID()),
                            userEventRow(e2, UUID.randomUUID()),
                            userEventRow(e3, UUID.randomUUID())));

            Event ev1 = sampleEvent(e1, UUID.randomUUID());

            Event ev3 = sampleEvent(e3, UUID.randomUUID());

            stubEventResponses(Optional.of(ev1), Optional.empty(), Optional.of(ev3));

            List<Event> result =
                    await(
                            new GetOutOfSyncEvents(
                                            USER_ID,
                                            clientCursor.toString(),
                                            userEventRepository,
                                            eventRepository)
                                    .serve());

            assertEquals(2, result.size());
            assertEquals(ev1, result.get(0));
            assertEquals(ev3, result.get(1));
        }

        @Test
        @DisplayName("returns empty list when all events are missing")
        void allMissing() throws Exception {
            UUID e1 = timeBased();
            UUID e2 = timeBased();

            stubUserEvents(
                    List.of(
                            userEventRow(e1, UUID.randomUUID()),
                            userEventRow(e2, UUID.randomUUID())));

            stubEventResponses(Optional.empty(), Optional.empty());

            List<Event> result =
                    await(
                            new GetOutOfSyncEvents(
                                            USER_ID,
                                            clientCursor.toString(),
                                            userEventRepository,
                                            eventRepository)
                                    .serve());

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Argument Propagation")
    class ArgumentPropagation {

        @Test
        @DisplayName("uses userId, cursor, and cursor's time bucket when querying user events")
        void passesUserEventArgs() throws Exception {
            stubUserEvents(Collections.emptyList());

            await(
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve());

            String expectedBucket =
                    BucketUtils.toTimeBucket(
                            Instant.ofEpochMilli(Uuids.unixTimestamp(clientCursor)));

            verify(userEventRepository)
                    .getUserEvents(eq(USER_ID), eq(clientCursor), eq(expectedBucket));
        }

        @Test
        @DisplayName(
                "uses conversationId, event's bucket, hashBucket=0, and eventId when querying event repo")
        void passesEventArgs() throws Exception {
            UUID eventId = timeBased();
            UUID conversationId = UUID.randomUUID();

            stubUserEvents(List.of(userEventRow(eventId, conversationId)));

            stubEventResponses(Optional.empty());

            await(
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve());

            String expectedBucket =
                    BucketUtils.toTimeBucket(Instant.ofEpochMilli(Uuids.unixTimestamp(eventId)));

            verify(eventRepository)
                    .getEvent(eq(conversationId), eq(expectedBucket), eq(0), eq(eventId));
        }

        @Test
        @DisplayName("issues exactly one event-repo call per user-event row")
        void oneCallPerRow() throws Exception {
            UUID e1 = timeBased();
            UUID e2 = timeBased();
            UUID e3 = timeBased();

            stubUserEvents(
                    List.of(
                            userEventRow(e1, UUID.randomUUID()),
                            userEventRow(e2, UUID.randomUUID()),
                            userEventRow(e3, UUID.randomUUID())));

            stubEventResponses(Optional.empty(), Optional.empty(), Optional.empty());

            await(
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve());

            verify(eventRepository, times(3))
                    .getEvent(any(UUID.class), anyString(), anyInt(), any(UUID.class));
        }

        @Test
        @DisplayName("calls userEventRepository exactly once regardless of row count")
        void oneCallToUserEvents() throws Exception {
            stubUserEvents(
                    List.of(
                            userEventRow(timeBased(), UUID.randomUUID()),
                            userEventRow(timeBased(), UUID.randomUUID())));

            stubEventResponses(Optional.empty(), Optional.empty());

            await(
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve());

            verify(userEventRepository, times(1)).getUserEvents(any(), any(), anyString());
        }

        @Test
        @DisplayName("passes distinct conversationIds to eventRepository as-is")
        void distinctConversationIds() throws Exception {
            UUID c1 = UUID.randomUUID();
            UUID c2 = UUID.randomUUID();

            UUID e1 = timeBased();
            UUID e2 = timeBased();

            stubUserEvents(List.of(userEventRow(e1, c1), userEventRow(e2, c2)));

            stubEventResponses(Optional.empty(), Optional.empty());

            await(
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve());

            ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);

            verify(eventRepository, times(2))
                    .getEvent(captor.capture(), anyString(), anyInt(), any(UUID.class));

            assertEquals(List.of(c1, c2), captor.getAllValues());
        }
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidation {

        @ParameterizedTest(name = "clientEventId=\"{0}\"")
        @ValueSource(
                strings = {
                    "",
                    "not-a-uuid",
                    "00000000-0000-0000-0000-00000000000",
                    "00000000-0000-0000-0000-0000000000000",
                    "ZZZZZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZZZZZZZZZ"
                })
        @DisplayName("serve() throws IllegalArgumentException for malformed clientEventId")
        void malformedClientEventId(String raw) {
            GetOutOfSyncEvents service =
                    new GetOutOfSyncEvents(USER_ID, raw, userEventRepository, eventRepository);

            assertThrows(IllegalArgumentException.class, service::serve);
        }

        @Test
        @DisplayName("serve() succeeds for a well-formed v1 clientEventId")
        void validV1ClientEventId() throws Exception {
            stubUserEvents(Collections.emptyList());

            List<Event> result =
                    await(
                            new GetOutOfSyncEvents(
                                            USER_ID,
                                            Uuids.timeBased().toString(),
                                            userEventRepository,
                                            eventRepository)
                                    .serve());

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Failure Propagation")
    class FailurePropagation {

        @Test
        @DisplayName("propagates failure from getUserEvents")
        void userEventsFails() {
            RuntimeException boom = new RuntimeException("user-events down");

            CompletableFuture<AsyncResultSet> failed = new CompletableFuture<>();

            failed.completeExceptionally(boom);

            when(userEventRepository.getUserEvents(any(), any(), anyString())).thenReturn(failed);

            CompletionStage<List<Event>> stage =
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve();

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    stage.toCompletableFuture()
                                            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertSame(boom, ex.getCause());
        }

        @Test
        @DisplayName("propagates failure from getEvent when any event lookup fails")
        void eventLookupFails() {
            UUID e1 = timeBased();
            UUID e2 = timeBased();

            stubUserEvents(
                    List.of(
                            userEventRow(e1, UUID.randomUUID()),
                            userEventRow(e2, UUID.randomUUID())));

            CompletableFuture<Optional<Event>> ok =
                    CompletableFuture.completedFuture(Optional.empty());

            CompletableFuture<Optional<Event>> failed = new CompletableFuture<>();

            RuntimeException boom = new RuntimeException("event-repo down");

            failed.completeExceptionally(boom);

            when(eventRepository.getEvent(any(), anyString(), anyInt(), any()))
                    .thenReturn(ok)
                    .thenReturn(failed);

            CompletionStage<List<Event>> stage =
                    new GetOutOfSyncEvents(
                                    USER_ID,
                                    clientCursor.toString(),
                                    userEventRepository,
                                    eventRepository)
                            .serve();

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    stage.toCompletableFuture()
                                            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertSame(boom, ex.getCause());
        }
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("two-arg constructor wires default repositories (smoke test via reflection)")
        void twoArgConstructorUsesDefaults() {
            Assumptions.assumeTrue(
                    hasCassandraSession(),
                    "Skipped: no Cassandra session available in this environment");

            GetOutOfSyncEvents service = new GetOutOfSyncEvents(USER_ID, clientCursor.toString());

            assertNotNull(service);
        }

        private static boolean hasCassandraSession() {
            try {
                Class.forName("server.CassandraManager").getMethod("getSession").invoke(null);

                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("Larger Workloads")
    class LargerWorkloads {

        @ParameterizedTest(name = "rowCount={0}")
        @ValueSource(ints = {1, 2, 5, 10, 50})
        @DisplayName("fans out one event lookup per row and returns all events")
        void manyRows(int rowCount) throws Exception {
            List<Row> rows = new ArrayList<>(rowCount);

            List<Event> expected = new ArrayList<>(rowCount);

            for (int i = 0; i < rowCount; i++) {
                UUID eventId = timeBased();
                UUID conversationId = UUID.randomUUID();

                rows.add(userEventRow(eventId, conversationId));

                expected.add(sampleEvent(eventId, conversationId));
            }

            stubUserEvents(rows);

            @SuppressWarnings("unchecked")
            Optional<Event>[] responses =
                    expected.stream().map(Optional::of).toArray(Optional[]::new);

            stubEventResponses(responses);

            List<Event> result =
                    await(
                            new GetOutOfSyncEvents(
                                            USER_ID,
                                            clientCursor.toString(),
                                            userEventRepository,
                                            eventRepository)
                                    .serve());

            assertEquals(expected, result);
        }
    }
}
