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

import database.ConversationMemberRepository;
import database.EventRepository;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.ConnectionTracker;
import server.MessageRouter;
import server.MessageServer;
import server.Session;

class ChatMessageHandlerTest {

    private static final String TEST_USER_ID = "user123";
    private static final String CONVERSATION_ID = "conversation-123";
    private static final String CLIENT_MESSAGE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CREATED_AT = "2026-09-30T17:30:00Z";

    private EmbeddedChannel senderChannel;
    private ConnectionTracker connectionTracker;

    private EventRepository eventRepository;
    private ConversationMemberRepository memberRepository;

    @BeforeEach
    void setup() {
        connectionTracker = new ConnectionTracker();

        eventRepository = mock(EventRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);

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

        senderChannel =
                new EmbeddedChannel(
                        new ChatMessageHandler(
                                new MessageRouter(
                                        connectionTracker, eventRepository, memberRepository)));

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
            connectionTracker.register("bob", recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(List.of(TEST_USER_ID, "bob"));

            String message =
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + CLIENT_MESSAGE_ID
                            + " "
                            + CREATED_AT
                            + " "
                            + base64Payload;

            senderChannel.writeInbound(message);

            Object outbound = recipientChannel.readOutbound();

            Assertions.assertNotNull(outbound);
            Assertions.assertTrue(outbound.toString().startsWith("EVENT MESSAGE_CREATED "));

            Assertions.assertTrue(outbound.toString().contains(CONVERSATION_ID));
            Assertions.assertTrue(outbound.toString().contains(TEST_USER_ID));
            Assertions.assertTrue(outbound.toString().endsWith(base64Payload));

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
            connectionTracker.register("bob", recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(List.of(TEST_USER_ID, "bob"));

            senderChannel.writeInbound(
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + CLIENT_MESSAGE_ID
                            + " "
                            + CREATED_AT
                            + " "
                            + base64Payload);

            Object outbound = recipientChannel.readOutbound();

            Assertions.assertNotNull(outbound);
            Assertions.assertTrue(outbound.toString().startsWith("EVENT MESSAGE_CREATED "));
            Assertions.assertTrue(outbound.toString().endsWith(base64Payload));

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testMultipleMessagesToSameConversation() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(List.of(TEST_USER_ID, "bob"));

            senderChannel.writeInbound(
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + CLIENT_MESSAGE_ID
                            + " "
                            + CREATED_AT
                            + " "
                            + "SGVsbG8=");

            String secondClientMessageId = "660e8400-e29b-41d4-a716-446655440000";

            senderChannel.writeInbound(
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + secondClientMessageId
                            + " "
                            + CREATED_AT
                            + " "
                            + "V29ybGQ=");

            Object first = recipientChannel.readOutbound();
            Object second = recipientChannel.readOutbound();

            Assertions.assertNotNull(first);
            Assertions.assertNotNull(second);

            Assertions.assertTrue(first.toString().endsWith("SGVsbG8="));
            Assertions.assertTrue(second.toString().endsWith("V29ybGQ="));

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testMessageDeliveredToConversationMembers() {

        EmbeddedChannel bobChannel = new EmbeddedChannel();
        EmbeddedChannel aliceChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", bobChannel);
            connectionTracker.register("alice", aliceChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(List.of("bob", "alice"));

            senderChannel.writeInbound(
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + CLIENT_MESSAGE_ID
                            + " "
                            + CREATED_AT
                            + " "
                            + "SGVsbG8=");

            Object bobMessage = bobChannel.readOutbound();
            Object aliceMessage = aliceChannel.readOutbound();

            Assertions.assertNotNull(bobMessage);
            Assertions.assertNotNull(aliceMessage);

            Assertions.assertTrue(bobMessage.toString().contains(CONVERSATION_ID));
            Assertions.assertTrue(aliceMessage.toString().contains(CONVERSATION_ID));

            Assertions.assertTrue(bobMessage.toString().endsWith("SGVsbG8="));
            Assertions.assertTrue(aliceMessage.toString().endsWith("SGVsbG8="));

        } finally {
            bobChannel.finishAndReleaseAll();
            aliceChannel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SEND",
                "SEND conversation-123",
                "SEND conversation-123 client-id",
                "SEND conversation-123 client-id 2026-09-30T17:30:00Z",
                "SEND  conversation-123 client-id 2026-09-30T17:30:00Z SGVsbG8=",
                " SEND conversation-123 client-id 2026-09-30T17:30:00Z SGVsbG8=",
                "SEND conversation-123 client-id 2026-09-30T17:30:00Z SGVsbG8= ",
                "send conversation-123 client-id 2026-09-30T17:30:00Z SGVsbG8="
            })
    void testInvalidSendMessageFormat(String invalidMessage) {

        senderChannel.writeInbound(invalidMessage);

        Assertions.assertEquals("ERROR Invalid SEND format", senderChannel.readOutbound());
    }

    @Test
    void testSendToConversationWithNoConnectedMembers() {

        when(memberRepository.findMemberIds(CONVERSATION_ID)).thenReturn(List.of("bob"));

        senderChannel.writeInbound(
                "SEND "
                        + CONVERSATION_ID
                        + " "
                        + CLIENT_MESSAGE_ID
                        + " "
                        + CREATED_AT
                        + " "
                        + "SGVsbG8=");

        /*
         * The event is persisted even though Bob is offline.
         * There should NOT be:
         *
         * ERROR Recipient not connected
         */
        Assertions.assertNull(senderChannel.readOutbound());
    }

    @Test
    void testSenderUserIdIsIncludedInEvent() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            when(memberRepository.findMemberIds(CONVERSATION_ID))
                    .thenReturn(List.of("alice123", "bob"));

            Session session = new Session();
            session.setUserId("alice123");

            senderChannel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);

            senderChannel.writeInbound(
                    "SEND "
                            + CONVERSATION_ID
                            + " "
                            + CLIENT_MESSAGE_ID
                            + " "
                            + CREATED_AT
                            + " "
                            + "SGVsbG8=");

            Object outbound = recipientChannel.readOutbound();

            Assertions.assertNotNull(outbound);
            Assertions.assertTrue(outbound.toString().contains("alice123"));

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }
}
