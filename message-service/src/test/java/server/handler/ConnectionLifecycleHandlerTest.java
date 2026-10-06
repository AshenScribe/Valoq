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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import server.ClientConnection;
import server.ConnectionTracker;

@DisplayName("ConnectionLifecycleHandler Tests")
class ConnectionLifecycleHandlerTest {

    private ConnectionTracker connectionTracker;
    private ConnectionLifecycleHandler handler;

    @BeforeEach
    void setUp() {
        connectionTracker = Mockito.mock(ConnectionTracker.class);
        handler = new ConnectionLifecycleHandler(connectionTracker);
    }

    private static final class InactiveRecorder extends ChannelInboundHandlerAdapter {

        private int inactiveCount;

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            inactiveCount++;
            ctx.fireChannelInactive();
        }

        private int getInactiveCount() {
            return inactiveCount;
        }
    }

    @Nested
    @DisplayName("Unauthenticated / Missing Connection")
    class UnauthenticatedTests {

        @Test
        @DisplayName("no tracked connection: no unregister, no markClosing, still fires downstream")
        void noTrackedConnection() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            channel.close();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, recorder.getInactiveCount());
            verifyNoInteractions(connectionTracker);
        }

        @Test
        @DisplayName("connection present but userId null: no unregister, no markClosing")
        void connectionWithoutUserId() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(null);

            channel.close();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, recorder.getInactiveCount());
            verify(connectionTracker, never()).unregister(any(), any());
            verify(conn, never()).markClosing();
        }

        @Test
        @DisplayName("connection without userId still fires downstream")
        void firesDownstreamEvenWithoutUserId() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(null);

            channel.close();

            Assertions.assertEquals(1, recorder.getInactiveCount());
        }
    }

    @Nested
    @DisplayName("Authenticated Connection")
    class AuthenticatedTests {

        @Test
        @DisplayName("authenticated connection: unregisters, marks closing, fires downstream")
        void authenticated() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            UUID userId = UUID.randomUUID();
            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(userId);

            channel.close();

            verify(connectionTracker, times(1)).unregister(userId, channel);
            verify(conn, times(1)).markClosing();
            Assertions.assertEquals(1, recorder.getInactiveCount());
        }

        @Test
        @DisplayName("unregister receives the exact channel from the context")
        void unregisterUsesContextChannel() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            UUID userId = UUID.randomUUID();
            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(userId);

            channel.close();

            ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);

            verify(connectionTracker).unregister(eq(userId), captor.capture());
            Assertions.assertSame(channel, captor.getValue());
        }

        @Test
        @DisplayName("markClosing called after unregister")
        void markClosingAfterUnregister() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            UUID userId = UUID.randomUUID();
            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(userId);

            channel.close();

            InOrder order = inOrder(connectionTracker, conn);
            order.verify(connectionTracker).unregister(eq(userId), eq(channel));
            order.verify(conn).markClosing();
        }

        @Test
        @DisplayName("distinct userIds across channels are unregistered independently")
        void distinctUsersPerChannel() {
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();

            ConnectionLifecycleHandler handlerA = new ConnectionLifecycleHandler(connectionTracker);
            ConnectionLifecycleHandler handlerB = new ConnectionLifecycleHandler(connectionTracker);

            InactiveRecorder recA = new InactiveRecorder();
            EmbeddedChannel chA = new EmbeddedChannel(handlerA, recA);
            ClientConnection connA = installConnection(chA);
            Mockito.when(connA.getUserId()).thenReturn(userA);

            InactiveRecorder recB = new InactiveRecorder();
            EmbeddedChannel chB = new EmbeddedChannel(handlerB, recB);
            ClientConnection connB = installConnection(chB);
            Mockito.when(connB.getUserId()).thenReturn(userB);

            chA.close();
            chB.close();

            verify(connectionTracker).unregister(userA, chA);
            verify(connectionTracker).unregister(userB, chB);
            verify(connA).markClosing();
            verify(connB).markClosing();

            Assertions.assertEquals(1, recA.getInactiveCount());
            Assertions.assertEquals(1, recB.getInactiveCount());
        }
    }

    @Nested
    @DisplayName("Downstream Propagation")
    class PropagationTests {

        @Test
        @DisplayName("channelInactive always fires downstream exactly once")
        void firesExactlyOnce() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            channel.close();

            Assertions.assertEquals(1, recorder.getInactiveCount());
        }

        @Test
        @DisplayName("fireChannelInactive is invoked even when no connection exists")
        void firesWithoutConnection() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            channel.close();

            Assertions.assertEquals(1, recorder.getInactiveCount());
        }

        @Test
        @DisplayName("no handler after this one: downstream fire is a safe no-op")
        void noDownstreamHandler() {
            EmbeddedChannel channel = new EmbeddedChannel(handler);

            Assertions.assertDoesNotThrow(() -> channel.close());
            Assertions.assertFalse(channel.isOpen());
        }
    }

    @Nested
    @DisplayName("Idempotency / Repeated Events")
    class IdempotencyTests {

        @Test
        @DisplayName("second close does not fire channelInactive a second time")
        void secondCloseIsNoOp() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            UUID userId = UUID.randomUUID();
            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(userId);

            channel.close();
            channel.close();

            Assertions.assertEquals(1, recorder.getInactiveCount());
            verify(connectionTracker, times(1)).unregister(eq(userId), any());
            verify(conn, times(1)).markClosing();
        }

        @Test
        @DisplayName("connection returns null after first inactive: second inactive is safe")
        void nullAfterInactive() {
            InactiveRecorder recorder = new InactiveRecorder();
            EmbeddedChannel channel = new EmbeddedChannel(handler, recorder);

            UUID userId = UUID.randomUUID();
            ClientConnection conn = installConnection(channel);
            Mockito.when(conn.getUserId()).thenReturn(userId).thenReturn(null);

            Assertions.assertDoesNotThrow(() -> channel.close());

            verify(connectionTracker, times(1)).unregister(eq(userId), any());
        }
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("null ConnectionTracker is accepted at construction")
        void nullTrackerAccepted() {
            Assertions.assertDoesNotThrow(() -> new ConnectionLifecycleHandler(null));
        }

        @Test
        @DisplayName("handler is not @Sharable")
        void notSharable() {
            Assertions.assertFalse(
                    ConnectionLifecycleHandler.class.isAnnotationPresent(
                            io.netty.channel.ChannelHandler.Sharable.class),
                    "handler holds per-connection state via ctx; " + "it must not be @Sharable");
        }
    }

    private static ClientConnection installConnection(EmbeddedChannel ch) {
        ClientConnection conn = Mockito.mock(ClientConnection.class);

        if (tryStaticSet(ch, conn)) {
            return conn;
        }

        if (tryStaticMap(ch, conn, "CONNECTIONS")) {
            return conn;
        }

        if (tryStaticMap(ch, conn, "CHANNEL_TO_CONNECTION")) {
            return conn;
        }

        if (tryChannelAttribute(ch, conn)) {
            return conn;
        }

        Assertions.fail(
                "Could not install a ClientConnection for the test channel. "
                        + "Update ConnectionLifecycleHandlerTest.installConnection(...) "
                        + "to match ClientConnection's actual storage: check "
                        + "ClientConnection.get(Channel) in production and mirror "
                        + "how it reads the mapping.");

        return conn;
    }

    private static boolean tryStaticSet(EmbeddedChannel ch, ClientConnection conn) {
        try {
            Method m =
                    ClientConnection.class.getDeclaredMethod(
                            "set", Channel.class, ClientConnection.class);

            m.setAccessible(true);
            m.invoke(null, ch, conn);

            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean tryStaticMap(
            EmbeddedChannel ch, ClientConnection conn, String fieldName) {
        try {
            Field f = ClientConnection.class.getDeclaredField(fieldName);

            f.setAccessible(true);

            Map<Channel, ClientConnection> map = (Map<Channel, ClientConnection>) f.get(null);

            map.put(ch, conn);

            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean tryChannelAttribute(EmbeddedChannel ch, ClientConnection conn) {
        for (Field f : ClientConnection.class.getDeclaredFields()) {
            if (!AttributeKey.class.isAssignableFrom(f.getType())) {
                continue;
            }

            try {
                f.setAccessible(true);
                Object key = f.get(null);

                if (key instanceof AttributeKey<?> ak) {
                    setAttr(ch, ak, conn);
                    return true;
                }
            } catch (ReflectiveOperationException e) {
                // try next field
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> void setAttr(EmbeddedChannel ch, AttributeKey<T> key, Object value) {
        ch.attr((AttributeKey<Object>) (AttributeKey<?>) key).set(value);
    }
}
