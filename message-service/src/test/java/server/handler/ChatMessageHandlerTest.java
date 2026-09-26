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

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import server.ConnectionTracker;
import server.MessageRouter;
import server.MessageServer;
import server.Session;

class ChatMessageHandlerTest {

    private static final String TEST_USER_ID = "user123";

    private EmbeddedChannel senderChannel;
    private ConnectionTracker connectionTracker;

    @BeforeEach
    void setup() {
        connectionTracker = new ConnectionTracker();

        senderChannel =
                new EmbeddedChannel(new ChatMessageHandler(new MessageRouter(connectionTracker)));

        Session session = new Session();
        session.setUserId(TEST_USER_ID);

        senderChannel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);
    }

    @AfterEach
    void tearDown() {
        senderChannel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @CsvSource({
        "user456, SGVsbG8gd29ybGQh",
        "user_123, U29tZSBvdGhlciB0ZXh0",
        "bob, SGVsbG8=",
        "alice, SGVsbA=="
    })
    void testValidSendMessageFormat(String recipientId, String base64Payload) {
        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(recipientId, recipientChannel);

            String message = "SEND " + recipientId + " " + base64Payload;

            senderChannel.writeInbound(message);

            Object outbound = recipientChannel.readOutbound();

            Assertions.assertEquals("FROM " + TEST_USER_ID + " " + base64Payload, outbound);

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

        String recipientId = "bob";
        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(recipientId, recipientChannel);

            senderChannel.writeInbound("SEND " + recipientId + " " + base64Payload);

            Assertions.assertEquals(
                    "FROM " + TEST_USER_ID + " " + base64Payload, recipientChannel.readOutbound());

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testMultipleMessagesToSameRecipient() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            senderChannel.writeInbound("SEND bob SGVsbG8=");

            senderChannel.writeInbound("SEND bob V29ybGQ=");

            Assertions.assertEquals(
                    "FROM " + TEST_USER_ID + " SGVsbG8=", recipientChannel.readOutbound());

            Assertions.assertEquals(
                    "FROM " + TEST_USER_ID + " V29ybGQ=", recipientChannel.readOutbound());

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testMessageDeliveredToCorrectRecipient() {

        EmbeddedChannel bobChannel = new EmbeddedChannel();
        EmbeddedChannel aliceChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", bobChannel);
            connectionTracker.register("alice", aliceChannel);

            senderChannel.writeInbound("SEND bob SGVsbG8=");

            Assertions.assertEquals(
                    "FROM " + TEST_USER_ID + " SGVsbG8=", bobChannel.readOutbound());

            Assertions.assertNull(aliceChannel.readOutbound());

        } finally {
            bobChannel.finishAndReleaseAll();
            aliceChannel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SEND SGVsbG8gd29ybGQh",
                "SEN user456 U29tZSBvdGhlciB0ZXh0",
                "send user456 SGVsbG8gd29ybGQh",
                "SEND\tuser456\tSGVsbG8gd29ybGQh",
                "SEND  ",
                "SEND",
                "SEND user456",
                "SEND user456 ",
                "SEND  user456 SGVsbG8=",
                " SEND user456 SGVsbG8=",
                "SEND user456 SGVsbG8= ",
            })
    void testInvalidSendMessageFormat(String invalidMessage) {

        senderChannel.writeInbound(invalidMessage);

        Assertions.assertEquals("ERROR Invalid SEND format", senderChannel.readOutbound());
    }

    @Test
    void testSendMessageToDisconnectedRecipient() {

        senderChannel.writeInbound("SEND unknownUser SGVsbG8=");

        Assertions.assertEquals("ERROR Recipient not connected", senderChannel.readOutbound());
    }

    @Test
    void testRecipientIdMaximumLength() {

        String recipientId = "a".repeat(64);

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register(recipientId, recipientChannel);

            senderChannel.writeInbound("SEND " + recipientId + " SGVsbG8=");

            Assertions.assertEquals(
                    "FROM " + TEST_USER_ID + " SGVsbG8=", recipientChannel.readOutbound());

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testRecipientIdGreaterThanMaximumLength() {

        String recipientId = "a".repeat(65);

        senderChannel.writeInbound("SEND " + recipientId + " SGVsbG8=");

        Assertions.assertEquals("ERROR Invalid SEND format", senderChannel.readOutbound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user 456", "user\t456", "user\n456", "user\r456"})
    void testRecipientIdCannotContainWhitespace(String recipientId) {

        senderChannel.writeInbound("SEND " + recipientId + " SGVsbG8=");

        Assertions.assertEquals("ERROR Invalid SEND format", senderChannel.readOutbound());
    }

    @Test
    void testSenderUserIdIsIncludedInFromMessage() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            Session session = new Session();
            session.setUserId("alice123");

            senderChannel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);

            senderChannel.writeInbound("SEND bob SGVsbG8=");

            Assertions.assertEquals("FROM alice123 SGVsbG8=", recipientChannel.readOutbound());

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testConnectionTrackerContainsRegisteredRecipient() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            Assertions.assertEquals(recipientChannel, connectionTracker.get("bob"));

            Assertions.assertEquals(1, connectionTracker.getSize());

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testConnectionTrackerUnregistersRecipient() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            Assertions.assertTrue(connectionTracker.unregister("bob", recipientChannel));

            Assertions.assertNull(connectionTracker.get("bob"));

            Assertions.assertEquals(0, connectionTracker.getSize());

        } finally {
            recipientChannel.finishAndReleaseAll();
        }
    }

    @Test
    void testUnregisterWithWrongChannelDoesNotRemoveConnection() {

        EmbeddedChannel recipientChannel = new EmbeddedChannel();
        EmbeddedChannel anotherChannel = new EmbeddedChannel();

        try {
            connectionTracker.register("bob", recipientChannel);

            Assertions.assertFalse(connectionTracker.unregister("bob", anotherChannel));

            Assertions.assertEquals(recipientChannel, connectionTracker.get("bob"));

        } finally {
            recipientChannel.finishAndReleaseAll();
            anotherChannel.finishAndReleaseAll();
        }
    }
}
