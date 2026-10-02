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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import database.ConversationMemberRepository;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protocol.BinaryMessages;
import protocol.Envelope;
import protocol.Opcode;
import server.model.Event;
import server.model.EventType;

class MessageRouterTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID ALICE_ID = UUID.randomUUID();
    private static final UUID BOB_ID = UUID.randomUUID();

    private ConnectionTracker connectionTracker;
    private EventRepository eventRepository;
    private ConversationMemberRepository memberRepository;
    private UserEventRepository userEventRepository;
    private MessageRouter messageRouter;

    @BeforeEach
    void setUp() {
        connectionTracker = new ConnectionTracker();
        eventRepository = mock(EventRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);
        userEventRepository = mock(UserEventRepository.class);
        messageRouter =
                new MessageRouter(
                        connectionTracker, eventRepository, memberRepository, userEventRepository);
    }

    @Test
    void testRouteSuccess() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());
        EmbeddedChannel bobChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        connectionTracker.register(ALICE_ID, aliceChannel);
        connectionTracker.register(BOB_ID, bobChannel);

        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440103");

        Event event =
                new Event(
                        ALICE_ID,
                        CONVERSATION_ID,
                        eventId,
                        EventType.MESSAGE_CREATED,
                        "SGVsbG8=",
                        Instant.now());

        when(memberRepository.findMemberIds(CONVERSATION_ID))
                .thenReturn(CompletableFuture.completedFuture(List.of(ALICE_ID, BOB_ID)));

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
    }

    @Test
    void testRouteOfflineMemberDoesNotFail() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        connectionTracker.register(ALICE_ID, aliceChannel);

        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440105");

        Event event =
                new Event(
                        ALICE_ID,
                        CONVERSATION_ID,
                        eventId,
                        EventType.MESSAGE_CREATED,
                        "SGVsbG8=",
                        Instant.now());

        when(memberRepository.findMemberIds(CONVERSATION_ID))
                .thenReturn(CompletableFuture.completedFuture(List.of(ALICE_ID, BOB_ID)));

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

        assertDoesNotThrow(() -> messageRouter.route(event).toCompletableFuture().join());

        assertNull(connectionTracker.get(BOB_ID));
        Envelope envelope = aliceChannel.readOutbound();
        assertNotNull(envelope);
        envelope.release();
    }

    @Test
    void testRouteSelfMessage() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        connectionTracker.register(ALICE_ID, aliceChannel);

        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440107");

        Event event =
                new Event(
                        ALICE_ID,
                        CONVERSATION_ID,
                        eventId,
                        EventType.MESSAGE_CREATED,
                        "U2VsZi1tZXNzYWdl",
                        Instant.now());

        when(memberRepository.findMemberIds(CONVERSATION_ID))
                .thenReturn(CompletableFuture.completedFuture(List.of(ALICE_ID)));

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
}
