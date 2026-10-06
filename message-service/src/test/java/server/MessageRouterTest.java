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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import database.ConversationMemberRepository;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import protocol.BinaryMessages;
import protocol.Envelope;
import protocol.Opcode;
import server.model.Event;
import server.model.EventType;

@DisplayName("MessageRouter Tests")
class MessageRouterTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID ALICE_ID = UUID.randomUUID();
    private static final UUID BOB_ID = UUID.randomUUID();
    private static final String NODE_ID = "node_random";
    private static final long TIMEOUT_SECONDS = 5L;

    private ConnectionTracker connectionTracker;
    private EventRepository eventRepository;
    private ConversationMemberRepository memberRepository;
    private UserEventRepository userEventRepository;
    private MessageRouter messageRouter;

    @BeforeEach
    void setUp() {
        connectionTracker = new ConnectionTracker(NODE_ID);
        eventRepository = mock(EventRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);
        userEventRepository = mock(UserEventRepository.class);
        messageRouter =
                new MessageRouter(
                        connectionTracker, eventRepository, memberRepository, userEventRepository);
    }

    private static Event sampleEvent(UUID senderId, String payload) {
        return new Event(
                senderId,
                CONVERSATION_ID,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440103"),
                EventType.MESSAGE_CREATED,
                payload,
                Instant.now());
    }

    private void stubMembers(UUID... memberIds) {
        when(memberRepository.findMemberIds(CONVERSATION_ID))
                .thenReturn(CompletableFuture.completedFuture(List.of(memberIds)));
    }

    private void stubMembersList(List<UUID> memberIds) {
        when(memberRepository.findMemberIds(CONVERSATION_ID))
                .thenReturn(CompletableFuture.completedFuture(memberIds));
    }

    private void stubSaveEventSuccess() {
        when(eventRepository.saveEvent(
                        any(UUID.class),
                        anyString(),
                        anyInt(),
                        any(UUID.class),
                        anyString(),
                        any(UUID.class),
                        any(UUID.class),
                        anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private void stubSaveUserEventSuccess() {
        when(userEventRepository.saveUserEvent(
                        any(UUID.class), anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private EmbeddedChannel register(UUID userId) {
        EmbeddedChannel ch = new EmbeddedChannel(DefaultChannelId.newInstance());
        connectionTracker.register(userId, ch);
        return ch;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void drainOutbound(EmbeddedChannel ch) {
        Object o;
        while ((o = ch.readOutbound()) != null) {
            if (o instanceof Envelope env) {
                env.release();
            }
        }
    }

    // ============================================================
    // Existing tests
    // ============================================================

    @Test
    void testRouteSuccess() {
        EmbeddedChannel aliceChannel = register(ALICE_ID);
        EmbeddedChannel bobChannel = register(BOB_ID);

        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440103");
        Event event =
                new Event(
                        ALICE_ID,
                        CONVERSATION_ID,
                        eventId,
                        EventType.MESSAGE_CREATED,
                        "SGVsbG8=",
                        Instant.now());

        stubMembers(ALICE_ID, BOB_ID);
        stubSaveEventSuccess();
        stubSaveUserEventSuccess();

        messageRouter.route(event).toCompletableFuture().join();

        Envelope envelope = bobChannel.readOutbound();
        assertNotNull(envelope);
        try {
            assertEquals(Opcode.EVENT, envelope.getHeader().opcode());
            Event decoded = BinaryMessages.decodeEvent(envelope.getBody());
            assertEquals(EventType.MESSAGE_CREATED, decoded.eventType());
            assertEquals(CONVERSATION_ID, decoded.conversationId());
            assertEquals(ALICE_ID, decoded.senderId());
            assertEquals("SGVsbG8=", decoded.payload());
        } finally {
            envelope.release();
        }

        drainOutbound(aliceChannel);
    }

    @Test
    void testRouteOfflineMemberDoesNotFail() {
        EmbeddedChannel aliceChannel = register(ALICE_ID);

        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440105");
        Event event =
                new Event(
                        ALICE_ID,
                        CONVERSATION_ID,
                        eventId,
                        EventType.MESSAGE_CREATED,
                        "SGVsbG8=",
                        Instant.now());

        stubMembers(ALICE_ID, BOB_ID);
        stubSaveEventSuccess();
        stubSaveUserEventSuccess();

        assertDoesNotThrow(() -> messageRouter.route(event).toCompletableFuture().join());

        assertNull(connectionTracker.get(BOB_ID));
        Envelope envelope = aliceChannel.readOutbound();
        assertNotNull(envelope);
        envelope.release();
    }

    @Test
    void testRouteSelfMessage() {
        EmbeddedChannel aliceChannel = register(ALICE_ID);

        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440107");
        Event event =
                new Event(
                        ALICE_ID,
                        CONVERSATION_ID,
                        eventId,
                        EventType.MESSAGE_CREATED,
                        "U2VsZi1tZXNzYWdl",
                        Instant.now());

        stubMembers(ALICE_ID);
        stubSaveEventSuccess();
        stubSaveUserEventSuccess();

        messageRouter.route(event).toCompletableFuture().join();

        Envelope envelope = aliceChannel.readOutbound();
        assertNotNull(envelope);
        try {
            assertEquals(Opcode.EVENT, envelope.getHeader().opcode());
            Event decoded = BinaryMessages.decodeEvent(envelope.getBody());
            assertEquals(EventType.MESSAGE_CREATED, decoded.eventType());
            assertEquals(CONVERSATION_ID, decoded.conversationId());
            assertEquals(ALICE_ID, decoded.senderId());
            assertEquals("U2VsZi1tZXNzYWdl", decoded.payload());
        } finally {
            envelope.release();
        }
    }

    @Nested
    @DisplayName("Empty Conversation")
    class EmptyConversationTests {

        @Test
        @DisplayName("no members: no broadcast, no user-event indexing, event still persisted")
        void noMembers() throws Exception {
            stubMembersList(Collections.emptyList());
            stubSaveEventSuccess();

            Event event = sampleEvent(ALICE_ID, "SGVsbG8=");

            UUID result = await(messageRouter.route(event));

            assertNotNull(result);
            verify(eventRepository, times(1))
                    .saveEvent(
                            eq(CONVERSATION_ID),
                            anyString(),
                            eq(0),
                            any(UUID.class),
                            eq(EventType.MESSAGE_CREATED.name()),
                            eq(ALICE_ID),
                            any(UUID.class),
                            eq("SGVsbG8="));
            verify(userEventRepository, never()).saveUserEvent(any(), anyString(), any(), any());
            assertNull(connectionTracker.get(ALICE_ID));
        }

        @Test
        @DisplayName("no members: returns a fresh eventId different from the input event's id")
        void assignsFreshEventId() throws Exception {
            stubMembersList(Collections.emptyList());
            stubSaveEventSuccess();

            Event event = sampleEvent(ALICE_ID, "p");
            UUID inputEventId = event.eventId();

            UUID returnedId = await(messageRouter.route(event));

            assertNotEquals(inputEventId, returnedId);
        }

        @Test
        @DisplayName("no members: completes without error even when nothing to do")
        void completesWithoutError() {
            stubMembersList(Collections.emptyList());
            stubSaveEventSuccess();

            assertDoesNotThrow(
                    () ->
                            messageRouter
                                    .route(sampleEvent(ALICE_ID, "p"))
                                    .toCompletableFuture()
                                    .join());
        }
    }

    @Nested
    @DisplayName("Multi-cast Fan-out")
    class MultiCastTests {

        @Test
        @DisplayName("online members receive the envelope; offline members are skipped silently")
        void mixedOnlineAndOffline() throws Exception {
            UUID carolId = UUID.randomUUID();
            UUID daveId = UUID.randomUUID();

            EmbeddedChannel aliceCh = register(ALICE_ID);
            EmbeddedChannel bobCh = register(BOB_ID);
            stubMembersList(List.of(ALICE_ID, BOB_ID, carolId, daveId));
            stubSaveEventSuccess();
            stubSaveUserEventSuccess();

            UUID returnedId = await(messageRouter.route(sampleEvent(ALICE_ID, "broadcast")));

            assertNotNull(returnedId);
            assertNotNull(aliceCh.readOutbound());
            assertNotNull(bobCh.readOutbound());
            assertNull(connectionTracker.get(carolId));
            assertNull(connectionTracker.get(daveId));

            verify(userEventRepository, times(1))
                    .saveUserEvent(eq(ALICE_ID), anyString(), eq(returnedId), eq(CONVERSATION_ID));
            verify(userEventRepository, times(1))
                    .saveUserEvent(eq(BOB_ID), anyString(), eq(returnedId), eq(CONVERSATION_ID));
            verify(userEventRepository, times(1))
                    .saveUserEvent(eq(carolId), anyString(), eq(returnedId), eq(CONVERSATION_ID));
            verify(userEventRepository, times(1))
                    .saveUserEvent(eq(daveId), anyString(), eq(returnedId), eq(CONVERSATION_ID));

            drainOutbound(aliceCh);
            drainOutbound(bobCh);
        }

        @Test
        @DisplayName("only sender online: only sender receives the envelope")
        void onlySenderOnline() throws Exception {
            UUID carolId = UUID.randomUUID();
            EmbeddedChannel aliceCh = register(ALICE_ID);

            stubMembersList(List.of(ALICE_ID, BOB_ID, carolId));
            stubSaveEventSuccess();
            stubSaveUserEventSuccess();

            await(messageRouter.route(sampleEvent(ALICE_ID, "hello")));

            Envelope received = aliceCh.readOutbound();
            assertNotNull(received);
            received.release();

            assertNull(connectionTracker.get(BOB_ID));
            assertNull(connectionTracker.get(carolId));
        }

        @Test
        @DisplayName(
                "only non-sender members online: sender does not receive own message if offline")
        void onlyNonSenderOnline() throws Exception {
            EmbeddedChannel bobCh = register(BOB_ID);

            stubMembersList(List.of(ALICE_ID, BOB_ID));
            stubSaveEventSuccess();
            stubSaveUserEventSuccess();

            await(messageRouter.route(sampleEvent(ALICE_ID, "hello")));

            assertNull(connectionTracker.get(ALICE_ID));
            Envelope env = bobCh.readOutbound();
            assertNotNull(env);
            env.release();
        }

        @ParameterizedTest(name = "onlineCount={0}, total={1}")
        @MethodSource("memberFanoutCases")
        @DisplayName("exactly the online members receive the envelope, all are indexed")
        void exactFanout(int onlineCount, int totalCount) throws Exception {
            List<UUID> members = new ArrayList<>(totalCount);
            List<EmbeddedChannel> onlineChannels = new ArrayList<>(onlineCount);
            for (int i = 0; i < totalCount; i++) {
                UUID id = UUID.randomUUID();
                members.add(id);
                if (i < onlineCount) {
                    onlineChannels.add(register(id));
                }
            }
            stubMembersList(members);
            stubSaveEventSuccess();
            stubSaveUserEventSuccess();

            UUID returnedId = await(messageRouter.route(sampleEvent(ALICE_ID, "p")));

            for (EmbeddedChannel ch : onlineChannels) {
                Envelope env = ch.readOutbound();
                assertNotNull(env);
                env.release();
                assertNull(ch.readOutbound());
            }

            for (UUID id : members) {
                verify(userEventRepository, times(1))
                        .saveUserEvent(eq(id), anyString(), eq(returnedId), eq(CONVERSATION_ID));
            }
        }

        static java.util.stream.Stream<Arguments> memberFanoutCases() {
            return java.util.stream.Stream.of(
                    Arguments.of(0, 1),
                    Arguments.of(1, 1),
                    Arguments.of(1, 3),
                    Arguments.of(2, 3),
                    Arguments.of(3, 3),
                    Arguments.of(2, 5),
                    Arguments.of(5, 5));
        }

        @Test
        @DisplayName("envelope broadcast to all online members carries the same eventId")
        void sameEventIdAcrossRecipients() throws Exception {
            EmbeddedChannel aliceCh = register(ALICE_ID);
            EmbeddedChannel bobCh = register(BOB_ID);

            stubMembersList(List.of(ALICE_ID, BOB_ID));
            stubSaveEventSuccess();
            stubSaveUserEventSuccess();

            UUID returnedId = await(messageRouter.route(sampleEvent(ALICE_ID, "same-id")));

            Envelope a = aliceCh.readOutbound();
            Envelope b = bobCh.readOutbound();
            assertNotNull(a);
            assertNotNull(b);
            try {
                Event ea = BinaryMessages.decodeEvent(a.getBody());
                Event eb = BinaryMessages.decodeEvent(b.getBody());
                assertEquals(returnedId, ea.eventId());
                assertEquals(returnedId, eb.eventId());
            } finally {
                a.release();
                b.release();
            }
        }
    }

    @Nested
    @DisplayName("Persistence Failures")
    class PersistenceFailureTests {

        @Test
        @DisplayName("saveEvent fails: route() completes exceptionally")
        void saveEventFails() {
            stubMembers(ALICE_ID, BOB_ID);

            RuntimeException dbDown = new RuntimeException("Cassandra timeout");
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(dbDown);

            when(eventRepository.saveEvent(
                            any(),
                            anyString(),
                            anyInt(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            anyString()))
                    .thenReturn((CompletableFuture) failed);

            CompletionStage<UUID> stage = messageRouter.route(sampleEvent(ALICE_ID, "p"));

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    stage.toCompletableFuture()
                                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(dbDown, ex.getCause());

            verify(userEventRepository, never()).saveUserEvent(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("saveEvent fails: no broadcast occurs")
        void saveEventFailsNoBroadcast() throws Exception {
            EmbeddedChannel bobCh = register(BOB_ID);
            stubMembers(ALICE_ID, BOB_ID);

            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("boom"));
            when(eventRepository.saveEvent(
                            any(),
                            anyString(),
                            anyInt(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            anyString()))
                    .thenReturn((CompletableFuture) failed);

            assertThrows(
                    ExecutionException.class,
                    () ->
                            messageRouter
                                    .route(sampleEvent(ALICE_ID, "p"))
                                    .toCompletableFuture()
                                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertNull(bobCh.readOutbound());
        }

        @Test
        @DisplayName("findMemberIds fails: route() completes exceptionally, no persistence")
        void findMembersFails() {
            CompletableFuture<List<UUID>> failed = new CompletableFuture<>();
            RuntimeException boom = new RuntimeException("member repo down");
            failed.completeExceptionally(boom);
            when(memberRepository.findMemberIds(CONVERSATION_ID)).thenReturn(failed);

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    messageRouter
                                            .route(sampleEvent(ALICE_ID, "p"))
                                            .toCompletableFuture()
                                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(boom, ex.getCause());

            verify(eventRepository, never())
                    .saveEvent(
                            any(),
                            anyString(),
                            anyInt(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            anyString());
            verify(userEventRepository, never()).saveUserEvent(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("one saveUserEvent fails: route() completes exceptionally with that cause")
        void oneIndexingFails() {
            stubMembers(ALICE_ID, BOB_ID);
            stubSaveEventSuccess();

            CompletableFuture<Object> ok = CompletableFuture.completedFuture(null);
            CompletableFuture<Object> failed = new CompletableFuture<>();
            RuntimeException indexingBoom = new RuntimeException("user_events write failed");
            failed.completeExceptionally(indexingBoom);

            when(userEventRepository.saveUserEvent(eq(ALICE_ID), anyString(), any(), any()))
                    .thenReturn((CompletableFuture) ok);
            when(userEventRepository.saveUserEvent(eq(BOB_ID), anyString(), any(), any()))
                    .thenReturn((CompletableFuture) failed);

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    messageRouter
                                            .route(sampleEvent(ALICE_ID, "p"))
                                            .toCompletableFuture()
                                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(indexingBoom, ex.getCause());
        }

        @Test
        @DisplayName("all saveUserEvent calls fail: exceptional completion is not masked")
        void allIndexingFails() {
            stubMembers(ALICE_ID, BOB_ID);
            stubSaveEventSuccess();

            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("cassandra unavailable"));
            when(userEventRepository.saveUserEvent(any(), anyString(), any(), any()))
                    .thenReturn((CompletableFuture) failed);

            assertThrows(
                    ExecutionException.class,
                    () ->
                            messageRouter
                                    .route(sampleEvent(ALICE_ID, "p"))
                                    .toCompletableFuture()
                                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("slow saveEvent (async pending) blocks completion until it finishes")
        void slowSaveEvent() throws Exception {
            stubMembers(ALICE_ID, BOB_ID);

            CompletableFuture<Object> pending = new CompletableFuture<>();
            when(eventRepository.saveEvent(
                            any(),
                            anyString(),
                            anyInt(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            anyString()))
                    .thenReturn((CompletableFuture) pending);

            CompletionStage<UUID> stage = messageRouter.route(sampleEvent(ALICE_ID, "p"));

            assertFalse(stage.toCompletableFuture().isDone());

            pending.complete(null);
            stubSaveUserEventSuccess();

            assertNotNull(await(stage));
        }

        @Test
        @DisplayName("slow saveUserEvent blocks completion until all futures resolve")
        void slowIndexing() throws Exception {
            stubMembers(ALICE_ID, BOB_ID);
            stubSaveEventSuccess();

            CompletableFuture<Object> pending = new CompletableFuture<>();
            when(userEventRepository.saveUserEvent(any(), anyString(), any(), any()))
                    .thenReturn((CompletableFuture) pending);

            CompletionStage<UUID> stage = messageRouter.route(sampleEvent(ALICE_ID, "p"));

            assertFalse(stage.toCompletableFuture().isDone());

            pending.complete(null);
            assertNotNull(await(stage));
        }
    }

    @Nested
    @DisplayName("routeReceipt")
    class RouteReceiptTests {

        @Test
        @DisplayName("watermark receipt: payload is WATERMARK and target is the watermark id")
        void watermarkReceipt() throws Exception {
            EmbeddedChannel bobCh = register(BOB_ID);
            stubMembers(ALICE_ID, BOB_ID);
            stubSaveEventSuccess();

            UUID watermarkId = UUID.fromString("550e8400-e29b-41d4-a716-446655440200");
            BinaryMessages.ReceiptRequest req =
                    BinaryMessages.ReceiptRequest.watermark(CONVERSATION_ID, watermarkId);

            await(messageRouter.routeReceipt(ALICE_ID, req, EventType.MESSAGE_READ));

            verify(eventRepository, times(1))
                    .saveEvent(
                            eq(CONVERSATION_ID),
                            anyString(),
                            eq(0),
                            any(UUID.class),
                            eq(EventType.MESSAGE_READ.name()),
                            eq(ALICE_ID),
                            eq(watermarkId),
                            eq("WATERMARK"));

            Envelope env = bobCh.readOutbound();
            assertNotNull(env);
            env.release();
        }

        @Test
        @DisplayName("explicit-list receipt: payload is comma-joined ids, target is first id")
        void explicitReceipt() throws Exception {
            stubMembers(ALICE_ID);
            stubSaveEventSuccess();

            UUID first = UUID.fromString("550e8400-e29b-41d4-a716-446655440300");
            UUID second = UUID.fromString("550e8400-e29b-41d4-a716-446655440301");
            BinaryMessages.ReceiptRequest req =
                    BinaryMessages.ReceiptRequest.explicit(CONVERSATION_ID, List.of(first, second));

            await(messageRouter.routeReceipt(ALICE_ID, req, EventType.MESSAGE_DELIVERED));

            verify(eventRepository)
                    .saveEvent(
                            eq(CONVERSATION_ID),
                            anyString(),
                            eq(0),
                            any(UUID.class),
                            eq(EventType.MESSAGE_DELIVERED.name()),
                            eq(ALICE_ID),
                            eq(first),
                            eq(first + "," + second));
        }

        @Test
        @DisplayName("empty explicit list: target entity is null")
        void emptyExplicitList() throws Exception {
            stubMembers(ALICE_ID);
            stubSaveEventSuccess();

            BinaryMessages.ReceiptRequest req =
                    BinaryMessages.ReceiptRequest.explicit(
                            CONVERSATION_ID, Collections.emptyList());

            await(messageRouter.routeReceipt(ALICE_ID, req, EventType.MESSAGE_DELIVERED));

            verify(eventRepository)
                    .saveEvent(
                            eq(CONVERSATION_ID),
                            anyString(),
                            eq(0),
                            any(UUID.class),
                            eq(EventType.MESSAGE_DELIVERED.name()),
                            eq(ALICE_ID),
                            isNull(),
                            eq(""));
        }

        @Test
        @DisplayName("no members: receipt is not indexed and no broadcast occurs")
        void noMembers() {
            stubMembersList(Collections.emptyList());
            stubSaveEventSuccess();

            BinaryMessages.ReceiptRequest req =
                    BinaryMessages.ReceiptRequest.watermark(CONVERSATION_ID, UUID.randomUUID());

            assertDoesNotThrow(
                    () ->
                            messageRouter
                                    .routeReceipt(ALICE_ID, req, EventType.MESSAGE_READ)
                                    .toCompletableFuture()
                                    .join());
        }

        @Test
        @DisplayName("saveEvent fails on receipt: stage completes exceptionally")
        void saveEventFails() {
            stubMembers(ALICE_ID);

            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("write failed"));
            when(eventRepository.saveEvent(
                            any(),
                            anyString(),
                            anyInt(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            anyString()))
                    .thenReturn((CompletableFuture) failed);

            BinaryMessages.ReceiptRequest req =
                    BinaryMessages.ReceiptRequest.watermark(CONVERSATION_ID, UUID.randomUUID());

            assertThrows(
                    ExecutionException.class,
                    () ->
                            messageRouter
                                    .routeReceipt(ALICE_ID, req, EventType.MESSAGE_READ)
                                    .toCompletableFuture()
                                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }
}
