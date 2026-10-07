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
package cache;

import base.BaseIntegrationTest;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protocol.Envelope;
import protocol.Opcode;
import server.ConnectionTracker;
import server.model.RoutedMessage;

@DisplayName("MessagePubSubListener E2E Tests")
class MessagePubSubListenerTest extends BaseIntegrationTest {

    private static final String NODE_ID = "node_test_primary";
    private static final long TIMEOUT_SECONDS = 5L;

    private MessagePubSubListener messagePubSubListener;
    private ConnectionTracker connectionTracker;
    private final List<EmbeddedChannel> channelsToClean = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        connectionTracker = new ConnectionTracker(NODE_ID);
        messagePubSubListener = new MessagePubSubListener(connectionTracker);
    }

    @AfterEach
    void tearDown() {
        for (EmbeddedChannel ch : channelsToClean) {
            if (ch.isOpen()) {
                ch.finishAndReleaseAll();
            }
        }
        channelsToClean.clear();
        connectionTracker.closeAll();
    }

    private EmbeddedChannel trackChannel(EmbeddedChannel channel) {
        channelsToClean.add(channel);
        return channel;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("Direct Listener Invocation")
    class DirectInvocationTests {

        @Test
        @DisplayName("delivers routed message envelope to active local user channel")
        void deliversRoutedMessageSuccessfully() {
            UUID userId = UUID.randomUUID();
            EmbeddedChannel channel = trackChannel(new EmbeddedChannel());
            connectionTracker.register(userId, channel);

            Envelope envelope = Envelope.createEmpty(Opcode.EVENT, 123);
            RoutedMessage routedMessage = new RoutedMessage(userId, envelope);

            messagePubSubListener.message("node:" + NODE_ID, routedMessage.toJson());

            Envelope received = channel.readOutbound();
            Assertions.assertNotNull(received, "Channel must receive the routed envelope");
            try {
                Assertions.assertEquals(Opcode.EVENT, received.getHeader().opcode());
                Assertions.assertEquals(123, received.getHeader().streamId());
            } finally {
                received.release();
            }
        }

        @Test
        @DisplayName("gracefully handles messages for users not connected to this node")
        void ignoresMissingUserConnection() {
            UUID unknownUserId = UUID.randomUUID();
            Envelope envelope = Envelope.createEmpty(Opcode.EVENT, 456);
            RoutedMessage routedMessage = new RoutedMessage(unknownUserId, envelope);

            Assertions.assertDoesNotThrow(
                    () -> messagePubSubListener.message("node:" + NODE_ID, routedMessage.toJson()),
                    "Should not throw exception when user has no local connections");
        }

        @Test
        @DisplayName("broadcasts to multiple active channels registered under the same user ID")
        void deliversToMultipleUserChannels() {
            UUID userId = UUID.randomUUID();
            EmbeddedChannel channelA = trackChannel(new EmbeddedChannel());
            EmbeddedChannel channelB = trackChannel(new EmbeddedChannel());

            connectionTracker.register(userId, channelA);
            connectionTracker.register(userId, channelB);

            Envelope envelope = Envelope.createEmpty(Opcode.READY, 99);
            RoutedMessage routedMessage = new RoutedMessage(userId, envelope);

            messagePubSubListener.message("node:" + NODE_ID, routedMessage.toJson());

            Envelope receivedA = channelA.readOutbound();
            Envelope receivedB = channelB.readOutbound();

            Assertions.assertNotNull(receivedA);
            Assertions.assertNotNull(receivedB);

            try {
                Assertions.assertEquals(Opcode.READY, receivedA.getHeader().opcode());
                Assertions.assertEquals(Opcode.READY, receivedB.getHeader().opcode());
            } finally {
                receivedA.release();
                receivedB.release();
            }
        }
    }

    @Nested
    @DisplayName("Live Redis Pub/Sub Integration")
    class RedisPubSubIntegrationTests {

        @Test
        @DisplayName(
                "pub/sub message propagated through real Redis cluster/container reaches listener")
        void liveRedisPubSubDelivery() throws Exception {
            UUID userId = UUID.randomUUID();
            EmbeddedChannel channel = trackChannel(new EmbeddedChannel());
            connectionTracker.register(userId, channel);

            RedisManager.getInstance().addPubSubListener(messagePubSubListener);

            Envelope envelope = Envelope.createEmpty(Opcode.EVENT, 777);
            RoutedMessage routedMessage = new RoutedMessage(userId, envelope);

            String channelName = "node:" + NODE_ID;

            RedisManager.getInstance()
                    .pubSubAsync()
                    .subscribe(channelName)
                    .toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long receiverCount = 0;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                receiverCount =
                        await(
                                RedisManager.getInstance()
                                        .pubSubAsync()
                                        .publish(channelName, routedMessage.toJson())
                                        .toCompletableFuture());
                if (receiverCount > 0) {
                    break;
                }
                Thread.sleep(50);
            }

            Assertions.assertTrue(receiverCount > 0, "Redis should acknowledge active subscribers");

            Envelope received = null;
            long receiveDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < receiveDeadline && received == null) {
                received = channel.readOutbound();
                if (received == null) {
                    Thread.sleep(50);
                }
            }

            Assertions.assertNotNull(
                    received, "Message published to Redis must trigger listener push");
            try {
                Assertions.assertEquals(Opcode.EVENT, received.getHeader().opcode());
                Assertions.assertEquals(777, received.getHeader().streamId());
            } finally {
                received.release();
            }
        }
    }

    @Nested
    @DisplayName("Malformed Payload Resilience")
    class ResilienceTests {

        @Test
        @DisplayName("malformed JSON payload throws runtime exception without crashing context")
        void malformedJsonPayloadThrowsException() {
            String malformedJson = "{invalid-json-structure";

            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> messagePubSubListener.message("node:" + NODE_ID, malformedJson),
                    "Invalid JSON format should surface parsing exceptions cleanly");
        }

        @ParameterizedTest(name = "emptyOrBlank=\"{0}\"")
        @ValueSource(strings = {"", "   ", "null"})
        @DisplayName("empty or blank messages are safely intercepted or rejected")
        void emptyOrBlankMessageHandling(String payload) {
            String input = "null".equals(payload) ? null : payload;
            Assertions.assertThrows(
                    Exception.class, () -> messagePubSubListener.message("node:" + NODE_ID, input));
        }
    }
}
