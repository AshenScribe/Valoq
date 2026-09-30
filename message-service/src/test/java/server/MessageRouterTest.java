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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import database.ConversationMemberRepository;
import database.EventRepository;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.model.Event;
import server.model.EventType;

class MessageRouterTest {

    private ConnectionTracker connectionTracker;
    private EventRepository eventRepository;
    private ConversationMemberRepository memberRepository;
    private MessageRouter messageRouter;

    @BeforeEach
    void setUp() {
        connectionTracker = new ConnectionTracker();
        eventRepository = mock(EventRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);

        messageRouter = new MessageRouter(connectionTracker, eventRepository, memberRepository);
    }

    @Test
    void testRouteSuccess() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        EmbeddedChannel bobChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        connectionTracker.register("alice", aliceChannel);
        connectionTracker.register("bob", bobChannel);

        Event event =
                new Event(
                        "alice",
                        "conversation-123",
                        "550e8400-e29b-41d4-a716-446655440000",
                        EventType.MESSAGE_CREATED,
                        "SGVsbG8=",
                        Instant.parse("2026-09-30T17:30:00Z"));

        when(memberRepository.findMemberIds("conversation-123"))
                .thenReturn(List.of("alice", "bob"));

        when(eventRepository.saveEvent(
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        messageRouter.route(event).toCompletableFuture().join();

        String message = bobChannel.readOutbound();

        assertNotNull(message);
        assertTrue(message.startsWith("EVENT MESSAGE_CREATED "));
        assertTrue(message.contains("conversation-123"));
        assertTrue(message.contains("alice"));
        assertTrue(message.endsWith("SGVsbG8="));
    }

    @Test
    void testRouteOfflineMemberDoesNotFail() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        connectionTracker.register("alice", aliceChannel);

        Event event =
                new Event(
                        "alice",
                        "conversation-123",
                        "550e8400-e29b-41d4-a716-446655440002",
                        EventType.MESSAGE_CREATED,
                        "SGVsbG8=",
                        Instant.parse("2026-09-30T17:30:00Z"));

        when(memberRepository.findMemberIds("conversation-123"))
                .thenReturn(List.of("alice", "bob"));

        when(eventRepository.saveEvent(
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertDoesNotThrow(() -> messageRouter.route(event).toCompletableFuture().join());

        assertNull(connectionTracker.get("bob"));
        assertNotNull(aliceChannel.readOutbound());
    }

    @Test
    void testRouteSelfMessage() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());

        connectionTracker.register("alice", aliceChannel);

        Event event =
                new Event(
                        "alice",
                        "conversation-123",
                        "550e8400-e29b-41d4-a716-446655440001",
                        EventType.MESSAGE_CREATED,
                        "U2VsZi1tZXNzYWdl",
                        Instant.parse("2026-09-30T17:30:00Z"));

        when(memberRepository.findMemberIds("conversation-123")).thenReturn(List.of("alice"));

        when(eventRepository.saveEvent(
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        messageRouter.route(event).toCompletableFuture().join();

        String message = aliceChannel.readOutbound();

        assertNotNull(message);
        assertTrue(message.startsWith("EVENT MESSAGE_CREATED "));
        assertTrue(message.contains("conversation-123"));
        assertTrue(message.contains("alice"));
        assertTrue(message.endsWith("U2VsZi1tZXNzYWdl"));
    }
}
