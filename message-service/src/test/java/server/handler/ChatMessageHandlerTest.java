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
package server.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import database.ConversationMemberRepository;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protocol.BinaryMessages;
import protocol.Envelope;
import protocol.Opcode;
import server.ConnectionTracker;
import server.MessageRouter;
import server.MessageServer;
import server.Session;
import server.model.Event;
import server.model.EventType;

class ChatMessageHandlerTest {

    private static final UUID TEST_USER_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final String CREATED_AT = "2026-09-30T17:30:00Z";
    private static final UUID ALICE_USER_ID = UUID.randomUUID();
    private static final UUID BOB_USER_ID = UUID.randomUUID();

    private EmbeddedChannel senderChannel;
    private ConnectionTracker connectionTracker;

    private EventRepository eventRepository;
    private ConversationMemberRepository memberRepository;
    private UserEventRepository userEventRepository;

    @BeforeEach
    void setup() {
        connectionTracker = new ConnectionTracker();

        eventRepository = mock(EventRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);
        userEventRepository = mock(UserEventRepository.class);

        when(eventRepository.saveEvent(
                        nullable(UUID.class),
                        anyString(),
                        anyInt(),
                        any(UUID.class),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        when(userEventRepository.saveUserEvent(
                        any(UUID.class), anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        senderChannel =
                new EmbeddedChannel(
                        new ChatMessageHandler(
                                new MessageRouter(
                                        connectionTracker,
                                        eventRepository,
                                        memberRepository,
                                        userEventRepository)));

        Session session = new Session();
        session.setUserId(TEST_USER_ID);

        senderChannel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);
    }

    @AfterEach
    void tearDown() {
        senderChannel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SGVsbG8gd29ybGQh", "U29tZSBvdGhlciB0ZXh0", "SGVsbG8=", "SGVsbA=="})
    void testValidSendMessageFormat(String base64Payload) {
        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(BOB_USER_ID, recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(
                            CompletableFuture.completedFuture(List.of(TEST_USER_ID, BOB_USER_ID)));

            String message = "SEND " + CONVERSATION_ID + " " + CREATED_AT + " " + base64Payload;
            senderChannel.writeInbound(message);

            Envelope outbound = recipientChannel.readOutbound();
            assertEventEnvelope(outbound, CONVERSATION_ID, TEST_USER_ID, base64Payload);

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SGVsbG8",
                "SGVsbG8gd29ybGQ=",
                "SGVsbG8gd29ybGQh",
                "QQ==",
                "QUI=",
                "QUJD",
                "MTIzNDU2Nzg5MA==",
                "YWJjZGVmZ2hpamtsbW5vcA=="
            })
    void testValidBase64Payload(String base64Payload) {
        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(BOB_USER_ID, recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(
                            CompletableFuture.completedFuture(List.of(TEST_USER_ID, BOB_USER_ID)));

            senderChannel.writeInbound(
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + Instant.parse(CREATED_AT)
                            + " "
                            + base64Payload);

            Envelope outbound = recipientChannel.readOutbound();
            assertEventEnvelope(outbound, CONVERSATION_ID, TEST_USER_ID, base64Payload);

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testMultipleMessagesToSameConversation() {
        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(BOB_USER_ID, recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(
                            CompletableFuture.completedFuture(List.of(TEST_USER_ID, BOB_USER_ID)));

            CompletableFuture<AsyncResultSet> firstSave = new CompletableFuture<>();
            CompletableFuture<AsyncResultSet> secondSave = new CompletableFuture<>();

            when(eventRepository.saveEvent(
                            nullable(UUID.class),
                            anyString(),
                            anyInt(),
                            any(UUID.class),
                            anyString(),
                            nullable(UUID.class),
                            nullable(UUID.class),
                            anyString()))
                    .thenReturn(firstSave)
                    .thenReturn(secondSave);

            senderChannel.writeInbound(
                    "SEND " + CONVERSATION_ID + " " + Instant.parse(CREATED_AT) + " " + "SGVsbG8=");
            Assertions.assertNull(recipientChannel.readOutbound());

            firstSave.complete(null);
            Envelope first = recipientChannel.readOutbound();
            assertEventEnvelope(first, CONVERSATION_ID, TEST_USER_ID, "SGVsbG8=");

            senderChannel.writeInbound(
                    "SEND " + CONVERSATION_ID + " " + Instant.parse(CREATED_AT) + " " + "V29ybGQ=");
            Assertions.assertNull(recipientChannel.readOutbound());

            secondSave.complete(null);
            Envelope second = recipientChannel.readOutbound();
            assertEventEnvelope(second, CONVERSATION_ID, TEST_USER_ID, "V29ybGQ=");

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testMessageDeliveredToConversationMembers() {
        EmbeddedChannel bobChannel = new EmbeddedChannel();
        EmbeddedChannel aliceChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(BOB_USER_ID, bobChannel);
            connectionTracker.register(ALICE_USER_ID, aliceChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(
                            CompletableFuture.completedFuture(List.of(BOB_USER_ID, ALICE_USER_ID)));

            senderChannel.writeInbound(
                    "SEND " + CONVERSATION_ID + " " + Instant.parse(CREATED_AT) + " " + "SGVsbG8=");

            Envelope bobMessage = bobChannel.readOutbound();
            Envelope aliceMessage = aliceChannel.readOutbound();

            assertEventEnvelope(bobMessage, CONVERSATION_ID, TEST_USER_ID, "SGVsbG8=");
            assertEventEnvelope(aliceMessage, CONVERSATION_ID, TEST_USER_ID, "SGVsbG8=");

        } finally {
            bobChannel.finishAndReleaseAll();
            aliceChannel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SEND",
                "SEND ",
                "SEND 5f08f2b9-b319-44b1-a007-9e1b9ead09fc",
                "SEND 5f08f2b9-b319-44b1-a007-9e1b9ead09fc 2026-09-30T17:30:00Z",
                "SEND  5f08f2b9-b319-44b1-a007-9e1b9ead09fc 2026-09-30T17:30:00Z SGVsbG8=",
                " SEND 5f08f2b9-b319-44b1-a007-9e1b9ead09fc 2026-09-30T17:30:00Z SGVsbG8=",
                "SEND 5f08f2b9-b319-44b1-a007-9e1b9ead09fc 2026-09-30T17:30:00Z SGVsbG8= ",
                "send 5f08f2b9-b319-44b1-a007-9e1b9ead09fc 2026-09-30T17:30:00Z SGVsbG8="
            })
    void testInvalidSendMessageFormat(String invalidMessage) {
        senderChannel.writeInbound(invalidMessage);
        Assertions.assertEquals("ERROR Invalid SEND format", senderChannel.readOutbound());
    }

    @Test
    void testSendToConversationWithNoConnectedMembers() {
        when(memberRepository.findMemberIds(CONVERSATION_ID))
                .thenReturn(CompletableFuture.completedFuture(List.of(BOB_USER_ID)));

        senderChannel.writeInbound(
                "SEND " + CONVERSATION_ID + " " + Instant.parse(CREATED_AT) + " " + "SGVsbG8=");

        Assertions.assertNull(senderChannel.readOutbound());
    }

    @Test
    void testSenderUserIdIsIncludedInEvent() {
        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(BOB_USER_ID, recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(
                            CompletableFuture.completedFuture(List.of(ALICE_USER_ID, BOB_USER_ID)));

            Session session = new Session();
            session.setUserId(ALICE_USER_ID);

            senderChannel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);

            senderChannel.writeInbound(
                    "SEND " + CONVERSATION_ID + " " + Instant.parse(CREATED_AT) + " " + "SGVsbG8=");

            Envelope outbound = recipientChannel.readOutbound();
            assertEventEnvelope(outbound, CONVERSATION_ID, ALICE_USER_ID, "SGVsbG8=");

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    private static void assertEventEnvelope(
            Envelope env, UUID expectedConvId, UUID expectedSenderId, String expectedPayload) {
        Assertions.assertNotNull(env, "Outbound envelope must not be null");
        try {
            Assertions.assertEquals(Opcode.EVENT, env.getHeader().opcode());
            Event event = BinaryMessages.decodeEvent(env.getBody());
            Assertions.assertEquals(expectedConvId, event.conversationId());
            Assertions.assertEquals(expectedSenderId, event.senderId());
            Assertions.assertEquals(EventType.MESSAGE_CREATED, event.eventType());
            Assertions.assertEquals(expectedPayload, event.payload());
        } finally {
            env.release();
        }
    }
}
