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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import protocol.BinaryMessages;
import protocol.BufferUtil;
import protocol.Envelope;
import protocol.Opcode;
import server.ClientConnection;
import server.ConnectionTracker;
import server.MessageRouter;
import server.model.Event;
import server.model.EventType;

@DisplayName("CommandDispatcherHandler Tests")
class CommandDispatcherHandlerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    private ConnectionTracker connectionTracker;
    private MessageRouter messageRouter;
    private UserEventRepository userEventRepository;
    private EventRepository eventRepository;
    private CommandDispatcherHandler dispatcher;

    @BeforeEach
    void setUp() {
        connectionTracker = mock(ConnectionTracker.class);
        messageRouter = mock(MessageRouter.class);
        userEventRepository = mock(UserEventRepository.class);
        eventRepository = mock(EventRepository.class);

        dispatcher =
                new CommandDispatcherHandler(
                        connectionTracker, messageRouter, userEventRepository, eventRepository);
    }

    private EmbeddedChannel newChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(dispatcher);
        when(connectionTracker.track(ch)).thenReturn(newClientConnection(ch));
        return ch;
    }

    private static ClientConnection newClientConnection(EmbeddedChannel ch) {
        try {
            Constructor<ClientConnection> ctor =
                    ClientConnection.class.getDeclaredConstructor(io.netty.channel.Channel.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ch);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not construct ClientConnection", e);
        }
    }

    private static Envelope initEnvelope(int streamId, String token) {
        var body = UnpooledByteBufAllocator.DEFAULT.buffer();
        BufferUtil.writeLongString(token, body);
        return Envelope.create(Opcode.INIT, streamId, body);
    }

    private static Envelope sendEnvelope(
            int streamId, UUID conversationId, Instant ts, String payload) {
        var body = UnpooledByteBufAllocator.DEFAULT.buffer();
        BufferUtil.writeUUID(conversationId, body);
        body.writeLong(ts.toEpochMilli());
        BufferUtil.writeLongString(payload, body);
        return Envelope.create(Opcode.SEND, streamId, body);
    }

    private static Envelope syncEnvelope(int streamId, UUID cursor) {
        var body = UnpooledByteBufAllocator.DEFAULT.buffer();
        BufferUtil.writeUUID(cursor, body);
        return Envelope.create(Opcode.SYNC, streamId, body);
    }

    private static Envelope receiptEnvelope(
            int streamId, Opcode opcode, BinaryMessages.ReceiptRequest req) {
        var body = UnpooledByteBufAllocator.DEFAULT.buffer();
        BinaryMessages.ReceiptRequest.encode(req, body);
        return Envelope.create(opcode, streamId, body);
    }

    private static JsonNode jwtWithSub(String sub) {
        return new ObjectMapper().createObjectNode().put("sub", sub);
    }

    private static void drainOutbound(EmbeddedChannel ch) {
        Object o;
        while ((o = ch.readOutbound()) != null) {
            if (o instanceof Envelope env) {
                env.release();
            }
        }
    }

    @Nested
    @DisplayName("Dispatch")
    class DispatchTests {

        private EmbeddedChannel channel;

        @ParameterizedTest(name = "opcode={0}")
        @EnumSource(
                value = Opcode.class,
                names = {"ERROR", "READY", "EVENT", "ACK"})
        @DisplayName("opcodes the dispatcher does not handle get ERROR + CLOSE")
        void unexpectedOpcodes(Opcode opcode) {
            channel = newChannel();
            Envelope env = Envelope.createEmpty(opcode, 5);

            assertDoesNotThrow(() -> channel.writeInbound(env));

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
                assertEquals(5, out.getHeader().streamId());
                assertEquals("Unexpected opcode: " + opcode, BufferUtil.readString(out.getBody()));
            } finally {
                out.release();
            }

            assertFalse(channel.isOpen());
        }

        @Test
        @DisplayName("ERROR opcode from a client yields ERROR + CLOSE")
        void errorOpcodeFromClient() {
            channel = newChannel();
            channel.writeInbound(Envelope.createEmpty(Opcode.ERROR, 1));

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            out.release();
            assertFalse(channel.isOpen());
        }
    }

    @Nested
    @DisplayName("INIT")
    class InitTests {

        private EmbeddedChannel channel;

        @Test
        @DisplayName("valid token with sub claim: registers, authenticates, sends READY")
        void validInit() {
            channel = newChannel();

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload("tok")).thenReturn(jwtWithSub(USER_ID.toString()));

                channel.writeInbound(initEnvelope(7, "tok"));

                Envelope out = channel.readOutbound();
                assertNotNull(out);
                try {
                    assertEquals(Opcode.READY, out.getHeader().opcode());
                    assertEquals(7, out.getHeader().streamId());
                } finally {
                    out.release();
                }

                verify(connectionTracker).register(eq(USER_ID), any());
                assertTrue(channel.isOpen());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("token with userId claim instead of sub is accepted")
        void userIdClaimFallback() {
            channel = newChannel();
            JsonNode payload =
                    new ObjectMapper().createObjectNode().put("userId", USER_ID.toString());

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload("tok")).thenReturn(payload);

                channel.writeInbound(initEnvelope(1, "tok"));

                Envelope out = channel.readOutbound();
                assertNotNull(out);
                out.release();

                verify(connectionTracker).register(eq(USER_ID), any());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("missing sub and userId: ERROR + CLOSE")
        void missingUserId() {
            channel = newChannel();
            JsonNode empty = new ObjectMapper().createObjectNode();

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload("tok")).thenReturn(empty);

                channel.writeInbound(initEnvelope(3, "tok"));

                Envelope out = channel.readOutbound();
                assertNotNull(out);
                try {
                    assertEquals(Opcode.ERROR, out.getHeader().opcode());
                    assertEquals("Missing userId claim", BufferUtil.readString(out.getBody()));
                } finally {
                    out.release();
                }

                assertFalse(channel.isOpen());
                verify(connectionTracker, never()).register(any(), any());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @ParameterizedTest(name = "userId=\"{0}\"")
        @ValueSource(strings = {"not-a-uuid", "", "12345"})
        @DisplayName("malformed userId in token: ERROR INVALID + CLOSE")
        void malformedUserId(String raw) {
            channel = newChannel();
            JsonNode payload = new ObjectMapper().createObjectNode().put("sub", raw);

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload("tok")).thenReturn(payload);

                channel.writeInbound(initEnvelope(4, "tok"));

                Envelope out = channel.readOutbound();
                assertNotNull(out);
                try {
                    assertEquals(Opcode.ERROR, out.getHeader().opcode());
                    assertEquals("INVALID", BufferUtil.readString(out.getBody()));
                } finally {
                    out.release();
                }

                assertFalse(channel.isOpen());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("JwtUtil throws: ERROR INVALID + CLOSE, envelope released")
        void jwtThrows() {
            channel = newChannel();

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload(anyString()))
                        .thenThrow(new RuntimeException("bad token"));

                channel.writeInbound(initEnvelope(2, "bad"));

                Envelope out = channel.readOutbound();
                assertNotNull(out);
                out.release();

                assertFalse(channel.isOpen());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("SEND")
    class SendTests {

        private EmbeddedChannel channel;

        private EmbeddedChannel authenticatedChannel() {
            EmbeddedChannel ch = newChannel();

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload(anyString())).thenReturn(jwtWithSub(USER_ID.toString()));

                ch.writeInbound(initEnvelope(1, "tok"));

                Envelope ready = ch.readOutbound();
                if (ready != null) {
                    ready.release();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return ch;
        }

        @Test
        @DisplayName("unauthenticated SEND: ERROR Session not initialized, no route call")
        void unauthenticated() {
            channel = newChannel();
            channel.writeInbound(sendEnvelope(9, CONVERSATION_ID, Instant.now(), "p"));

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
                assertEquals("ERROR Session not initialized", BufferUtil.readString(out.getBody()));
            } finally {
                out.release();
            }

            verifyNoInteractions(messageRouter);
        }

        @Test
        @DisplayName("authenticated SEND: routes event and replies with ACK")
        void authenticated() {
            UUID persistedId = UUID.randomUUID();

            when(messageRouter.route(any(Event.class)))
                    .thenReturn(CompletableFuture.completedFuture(persistedId));

            channel = authenticatedChannel();

            channel.writeInbound(
                    sendEnvelope(
                            9,
                            CONVERSATION_ID,
                            Instant.ofEpochMilli(1_700_000_000_000L),
                            "payload"));

            channel.runPendingTasks();

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ACK, out.getHeader().opcode());
                assertEquals(9, out.getHeader().streamId());
                assertEquals(persistedId, BinaryMessages.decodeAckResponse(out.getBody()));
            } finally {
                out.release();
            }

            verify(messageRouter, times(1)).route(any(Event.class));
        }

        @Test
        @DisplayName("SEND with malformed body: ERROR Invalid SEND format, envelope released")
        void malformedBody() {
            channel = authenticatedChannel();

            Envelope bad =
                    Envelope.create(
                            Opcode.SEND,
                            11,
                            UnpooledByteBufAllocator.DEFAULT
                                    .buffer()
                                    .writeBytes(new byte[] {1, 2, 3}));

            channel.writeInbound(bad);

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
                assertEquals("ERROR Invalid SEND format", BufferUtil.readString(out.getBody()));
            } finally {
                out.release();
            }

            verifyNoInteractions(messageRouter);
        }

        @Test
        @DisplayName("route() failure: ERROR emitted, no ACK; channel stays open")
        void routeFailure() {
            CompletableFuture<UUID> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("db down"));

            when(messageRouter.route(any(Event.class))).thenReturn(failed);

            channel = authenticatedChannel();
            channel.writeInbound(sendEnvelope(9, CONVERSATION_ID, Instant.now(), "p"));

            channel.runPendingTasks();

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
                assertEquals(9, out.getHeader().streamId());
                assertEquals("ERROR routing failed", BufferUtil.readString(out.getBody()));
            } finally {
                out.release();
            }

            assertNull(channel.readOutbound(), "only one outbound frame should be emitted");
            assertTrue(channel.isOpen());
        }

        @Test
        @DisplayName("many SENDs: 64+ in flight toggles autoRead off, draining turns it back on")
        void backPressure() {
            var gate = new CompletableFuture<UUID>();

            when(messageRouter.route(any(Event.class))).thenReturn(gate);

            channel = authenticatedChannel();
            assertTrue(channel.config().isAutoRead());

            for (int i = 0; i < 64; i++) {
                channel.writeInbound(sendEnvelope(i, CONVERSATION_ID, Instant.now(), "p"));
            }

            channel.runPendingTasks();
            assertFalse(channel.config().isAutoRead());

            gate.complete(UUID.randomUUID());
            channel.runPendingTasks();
            drainOutbound(channel);

            assertTrue(channel.config().isAutoRead());
        }

        @Test
        @DisplayName("only one route() runs concurrently; second waits for first")
        void serializedRouting() {
            var first = new CompletableFuture<UUID>();
            var second = new CompletableFuture<UUID>();

            when(messageRouter.route(any(Event.class))).thenReturn(first).thenReturn(second);

            channel = authenticatedChannel();

            channel.writeInbound(sendEnvelope(1, CONVERSATION_ID, Instant.now(), "a"));

            channel.writeInbound(sendEnvelope(2, CONVERSATION_ID, Instant.now(), "b"));

            channel.runPendingTasks();

            verify(messageRouter, times(1)).route(any(Event.class));

            first.complete(UUID.randomUUID());
            channel.runPendingTasks();

            verify(messageRouter, times(2)).route(any(Event.class));

            second.complete(UUID.randomUUID());
            channel.runPendingTasks();

            drainOutbound(channel);
        }
    }

    @Nested
    @DisplayName("SYNC")
    class SyncTests {

        private EmbeddedChannel channel;

        private EmbeddedChannel authenticatedChannel() {
            EmbeddedChannel ch = newChannel();

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload(anyString())).thenReturn(jwtWithSub(USER_ID.toString()));

                ch.writeInbound(initEnvelope(1, "tok"));

                Envelope ready = ch.readOutbound();
                if (ready != null) {
                    ready.release();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return ch;
        }

        @Test
        @DisplayName("unauthenticated SYNC: ERROR Session not initialized")
        void unauthenticated() {
            channel = newChannel();
            channel.writeInbound(syncEnvelope(5, Uuids.timeBased()));

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
            } finally {
                out.release();
            }
        }

        @Test
        @DisplayName("authenticated SYNC with no results: no EVENT frames emitted")
        void emptySyncResult() {
            var rs = mock(com.datastax.oss.driver.api.core.cql.AsyncResultSet.class);

            when(rs.currentPage()).thenReturn(List.of());

            when(userEventRepository.getUserEvents(any(), any(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(rs));

            channel = authenticatedChannel();
            channel.writeInbound(syncEnvelope(5, Uuids.timeBased()));
            channel.runPendingTasks();

            assertNull(channel.readOutbound());
        }

        @Test
        @DisplayName("SYNC with malformed body: ERROR Invalid SYNC format")
        void malformedBody() {
            channel = authenticatedChannel();

            Envelope bad =
                    Envelope.create(
                            Opcode.SYNC,
                            5,
                            UnpooledByteBufAllocator.DEFAULT.buffer().writeBytes(new byte[] {1}));

            channel.writeInbound(bad);

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
                assertEquals("ERROR Invalid SYNC format", BufferUtil.readString(out.getBody()));
            } finally {
                out.release();
            }
        }
    }

    @Nested
    @DisplayName("Receipts")
    class ReceiptTests {

        private EmbeddedChannel channel;

        private EmbeddedChannel authenticatedChannel() {
            EmbeddedChannel ch = newChannel();

            try (MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class)) {
                JwtUtil util = mock(JwtUtil.class);
                jwt.when(JwtUtil::getInstance).thenReturn(util);
                when(util.decodeToPayload(anyString())).thenReturn(jwtWithSub(USER_ID.toString()));

                ch.writeInbound(initEnvelope(1, "tok"));

                Envelope ready = ch.readOutbound();
                if (ready != null) {
                    ready.release();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return ch;
        }

        @ParameterizedTest(name = "opcode={0}")
        @EnumSource(
                value = Opcode.class,
                names = {"ACK_DELIVERED", "ACK_READ"})
        @DisplayName("receipt routes through MessageRouter.routeReceipt")
        void receiptRoutes(Opcode opcode) {
            when(messageRouter.routeReceipt(any(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            channel = authenticatedChannel();

            var req = BinaryMessages.ReceiptRequest.watermark(CONVERSATION_ID, UUID.randomUUID());

            channel.writeInbound(receiptEnvelope(3, opcode, req));
            channel.runPendingTasks();

            EventType expected =
                    opcode == Opcode.ACK_READ
                            ? EventType.MESSAGE_READ
                            : EventType.MESSAGE_DELIVERED;

            verify(messageRouter)
                    .routeReceipt(
                            eq(USER_ID), any(BinaryMessages.ReceiptRequest.class), eq(expected));

            assertNull(channel.readOutbound());
        }

        @Test
        @DisplayName("unauthenticated receipt: ERROR Session not initialized")
        void unauthenticatedReceipt() {
            channel = newChannel();

            var req = BinaryMessages.ReceiptRequest.watermark(CONVERSATION_ID, UUID.randomUUID());

            channel.writeInbound(receiptEnvelope(3, Opcode.ACK_READ, req));

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
            } finally {
                out.release();
            }
        }

        @Test
        @DisplayName("malformed receipt body: ERROR Invalid receipt format")
        void malformedReceipt() {
            channel = authenticatedChannel();

            Envelope bad =
                    Envelope.create(
                            Opcode.ACK_READ,
                            3,
                            UnpooledByteBufAllocator.DEFAULT.buffer().writeBytes(new byte[] {1}));

            channel.writeInbound(bad);

            Envelope out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertEquals(Opcode.ERROR, out.getHeader().opcode());
                assertEquals("ERROR Invalid receipt format", BufferUtil.readString(out.getBody()));
            } finally {
                out.release();
            }

            verifyNoInteractions(messageRouter);
        }
    }

    @Nested
    @DisplayName("exceptionCaught")
    class ExceptionCaughtTests {

        private EmbeddedChannel channel;

        @Test
        @DisplayName("pipeline exception closes the channel")
        void closesOnException() {
            channel = newChannel();
            channel.pipeline().fireExceptionCaught(new RuntimeException("boom"));

            assertFalse(channel.isOpen());
        }

        @Test
        @DisplayName("exception after close does not throw")
        void exceptionAfterClose() {
            channel = newChannel();
            channel.close();

            assertDoesNotThrow(
                    () -> channel.pipeline().fireExceptionCaught(new RuntimeException("boom")));
        }
    }
}
