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

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ClientConnection Unit Tests")
class ClientConnectionTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
    }

    @AfterEach
    void tearDown() {
        if (channel.isOpen()) {
            channel.finishAndReleaseAll();
        }
    }

    @Nested
    @DisplayName("Initialization & Channel Attachment")
    class InitializationTests {

        @Test
        @DisplayName("constructor rejects null channel with NullPointerException")
        void nullChannelThrows() {
            Assertions.assertThrows(NullPointerException.class, () -> new ClientConnection(null));
        }

        @Test
        @DisplayName("initial state is CONNECTED with null userId and valid connectionId")
        void initialProperties() {
            ClientConnection conn = new ClientConnection(channel);

            Assertions.assertNotNull(conn.getConnectionId());
            Assertions.assertEquals(channel, conn.getChannel());
            Assertions.assertEquals(ClientConnection.State.CONNECTED, conn.getState());
            Assertions.assertNull(conn.getUserId());
            Assertions.assertFalse(conn.isAuthenticated());
            Assertions.assertNotNull(conn.getConnectedAt());
            Assertions.assertEquals(0L, conn.getMessagesDelivered());
        }

        @Test
        @DisplayName("attachToChannel binds instance to channel attribute key")
        void attachToChannel() {
            ClientConnection conn = new ClientConnection(channel);
            conn.attachToChannel();

            ClientConnection retrieved = ClientConnection.get(channel);
            Assertions.assertSame(conn, retrieved);
        }
    }

    @Nested
    @DisplayName("Authentication State Transitions")
    class AuthenticationTests {

        @Test
        @DisplayName("authenticate updates state to AUTHENTICATED and sets userId")
        void successfulAuthentication() {
            ClientConnection conn = new ClientConnection(channel);
            UUID userId = UUID.randomUUID();

            conn.authenticate(userId);

            Assertions.assertEquals(ClientConnection.State.AUTHENTICATED, conn.getState());
            Assertions.assertEquals(userId, conn.getUserId());
            Assertions.assertTrue(conn.isAuthenticated());
        }

        @Test
        @DisplayName("authenticate rejects null userId with NullPointerException")
        void nullUserIdThrows() {
            ClientConnection conn = new ClientConnection(channel);
            Assertions.assertThrows(NullPointerException.class, () -> conn.authenticate(null));
        }

        @Test
        @DisplayName("markClosing changes state to CLOSING")
        void markClosing() {
            ClientConnection conn = new ClientConnection(channel);
            conn.markClosing();

            Assertions.assertEquals(ClientConnection.State.CLOSING, conn.getState());
            Assertions.assertFalse(conn.isAuthenticated());
        }
    }

    @Nested
    @DisplayName("Message Delivery Tracking")
    class MessageDeliveryTests {

        @Test
        @DisplayName("send writes string to active channel and increments counter")
        void sendToActiveChannel() {
            ClientConnection conn = new ClientConnection(channel);
            conn.send("TEST_MSG");

            Assertions.assertEquals(1L, conn.getMessagesDelivered());
            String written = channel.readOutbound();
            Assertions.assertEquals("TEST_MSG\n", written);
        }

        @Test
        @DisplayName("send appends newline only if missing")
        void sendAppendsNewline() {
            ClientConnection conn = new ClientConnection(channel);
            conn.send("ALREADY_HAS_NEWLINE\n");

            String written = channel.readOutbound();
            Assertions.assertEquals("ALREADY_HAS_NEWLINE\n", written);
        }

        @Test
        @DisplayName("send on inactive/closed channel does not increment counter or write")
        void sendOnClosedChannel() {
            ClientConnection conn = new ClientConnection(channel);
            channel.close();

            conn.send("IGNORED");

            Assertions.assertEquals(0L, conn.getMessagesDelivered());
            Assertions.assertNull(channel.readOutbound());
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("connections are distinct based on random connectionId")
        void distinctConnections() {
            ClientConnection c1 = new ClientConnection(channel);
            ClientConnection c2 = new ClientConnection(channel);

            Assertions.assertNotEquals(c1, c2);
            Assertions.assertEquals(c1, c1);
            Assertions.assertNotEquals(c1, null);
            Assertions.assertNotEquals(c1, "some-string");
        }
    }
}
