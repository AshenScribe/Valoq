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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import server.handler.IdleConnectionReaperHandler;

@DisplayName("IdleConnectionReaperHandler Tests")
class IdleConnectionReaperHandlerTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel newChannel() {
        return new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE);
    }

    private static IdleStateEvent eventFor(IdleState state) {
        return switch (state) {
            case READER_IDLE -> IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE -> IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            case ALL_IDLE -> IdleStateEvent.ALL_IDLE_STATE_EVENT;
        };
    }

    private static final class CaptureHandler
            extends io.netty.channel.ChannelInboundHandlerAdapter {

        private final List<Object> events = new ArrayList<>();
        private int userEventCount;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            userEventCount++;
            events.add(evt);
            ctx.fireUserEventTriggered(evt);
        }

        private List<Object> getEvents() {
            return events;
        }

        private int getUserEventCount() {
            return userEventCount;
        }
    }

    @Nested
    @DisplayName("Singleton / Sharability")
    class SingletonTests {

        @Test
        @DisplayName("INSTANCE is a non-null singleton")
        void instanceNonNull() {
            assertNotNull(IdleConnectionReaperHandler.INSTANCE);
            assertSame(IdleConnectionReaperHandler.INSTANCE, IdleConnectionReaperHandler.INSTANCE);
        }

        @Test
        @DisplayName("class is final")
        void classIsFinal() {
            assertTrue(Modifier.isFinal(IdleConnectionReaperHandler.class.getModifiers()));
        }

        @Test
        @DisplayName("constructor is private")
        void constructorIsPrivate() throws Exception {
            Constructor<IdleConnectionReaperHandler> ctor =
                    IdleConnectionReaperHandler.class.getDeclaredConstructor();
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        }

        @Test
        @DisplayName("class is annotated @Sharable")
        void classIsSharable() {
            assertTrue(
                    IdleConnectionReaperHandler.class.isAnnotationPresent(
                            io.netty.channel.ChannelHandler.Sharable.class));
        }

        @Test
        @DisplayName("same INSTANCE can be reused across many channels")
        void sharableAcrossChannels() {
            EmbeddedChannel c1 = newChannel();
            EmbeddedChannel c2 = newChannel();
            EmbeddedChannel c3 = newChannel();
            try {
                c1.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
                c2.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
                c3.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);

                assertFalse(c1.isOpen());
                assertFalse(c2.isOpen());
                assertTrue(c3.isOpen());
            } finally {
                c1.finishAndReleaseAll();
                c2.finishAndReleaseAll();
                c3.finishAndReleaseAll();
            }
        }
    }

    @Nested
    @DisplayName("Closing Idle States")
    class ClosingStates {

        @ParameterizedTest(name = "state={0}")
        @EnumSource(
                value = IdleState.class,
                names = {"READER_IDLE", "ALL_IDLE"})
        @DisplayName("READER_IDLE and ALL_IDLE close the channel")
        void closesOnReaderOrAllIdle(IdleState state) {
            channel = newChannel();
            assertTrue(channel.isOpen());

            channel.pipeline().fireUserEventTriggered(eventFor(state));

            assertFalse(channel.isOpen(), "channel should be closed for " + state);
        }

        @Test
        @DisplayName("READER_IDLE closes the channel")
        void closesOnReaderIdle() {
            channel = newChannel();
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
            assertFalse(channel.isOpen());
        }

        @Test
        @DisplayName("ALL_IDLE closes the channel")
        void closesOnAllIdle() {
            channel = newChannel();
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
            assertFalse(channel.isOpen());
        }

        @Test
        @DisplayName("closing the channel does not propagate the idle event downstream")
        void doesNotPropagateIdleEventWhenClosing() {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);

            assertFalse(channel.isOpen());
            assertEquals(0, capture.getUserEventCount());
        }

        @Test
        @DisplayName("subsequent idle events after close are safely ignored")
        void subsequentIdleEventsAfterClose() {
            channel = newChannel();

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
            assertFalse(channel.isOpen());

            assertDoesNotThrow(
                    () -> {
                        channel.pipeline()
                                .fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
                        channel.pipeline()
                                .fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
                    });
        }
    }

    @Nested
    @DisplayName("Pass-through Idle States")
    class PassThroughStates {

        @Test
        @DisplayName("WRITER_IDLE does not close the channel and is propagated")
        void writerIdlePassesThrough() {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);

            assertTrue(channel.isOpen());
            assertEquals(1, capture.getUserEventCount());
            assertSame(IdleStateEvent.WRITER_IDLE_STATE_EVENT, capture.getEvents().get(0));
        }

        @Test
        @DisplayName("WRITER_IDLE is the only pass-through idle state")
        void writerIdleOnlyPassThrough() {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            IdleStateEvent evt = eventFor(IdleState.WRITER_IDLE);
            channel.pipeline().fireUserEventTriggered(evt);

            assertTrue(channel.isOpen());
            assertEquals(1, capture.getUserEventCount());
            assertSame(evt, capture.getEvents().get(0));
        }
    }

    @Nested
    @DisplayName("Non-idle Events")
    class NonIdleEvents {

        @ParameterizedTest(name = "event={0}")
        @MethodSource("nonIdleEvents")
        @DisplayName("non-IdleStateEvent user events pass through untouched")
        void nonIdleEventPassesThrough(Object evt) {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            channel.pipeline().fireUserEventTriggered(evt);

            assertTrue(channel.isOpen());
            assertEquals(1, capture.getUserEventCount());
            assertSame(evt, capture.getEvents().get(0));
        }

        static Stream<Object> nonIdleEvents() {
            return Stream.of(
                    "plain string",
                    Integer.valueOf(42),
                    new Object(),
                    new RuntimeException("boom"));
        }

        @Test
        @DisplayName("multiple heterogeneous events in sequence all pass through")
        void sequenceOfEvents() {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            Object a = "a";
            Object b = Integer.valueOf(7);
            IdleStateEvent writerIdle = IdleStateEvent.WRITER_IDLE_STATE_EVENT;

            channel.pipeline().fireUserEventTriggered(a);
            channel.pipeline().fireUserEventTriggered(b);
            channel.pipeline().fireUserEventTriggered(writerIdle);

            assertTrue(channel.isOpen());
            assertEquals(3, capture.getUserEventCount());
            assertSame(a, capture.getEvents().get(0));
            assertSame(b, capture.events.get(1));
            assertSame(writerIdle, capture.events.get(2));
        }
    }

    @Nested
    @DisplayName("Sequencing")
    class Sequencing {

        @Test
        @DisplayName(
                "WRITER_IDLE then READER_IDLE: writer propagated, reader closes and is swallowed")
        void writerThenReader() {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);

            assertFalse(channel.isOpen());
            assertEquals(1, capture.getUserEventCount());
            assertSame(IdleStateEvent.WRITER_IDLE_STATE_EVENT, capture.getEvents().get(0));
        }

        @Test
        @DisplayName("READER_IDLE then WRITER_IDLE: reader closes, writer is never delivered")
        void readerThenWriter() {
            CaptureHandler capture = new CaptureHandler();
            channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE, capture);

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
            assertFalse(channel.isOpen());

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);

            assertEquals(0, capture.getUserEventCount());
        }
    }

    @Nested
    @DisplayName("Handler Direct Invocation")
    class DirectInvocation {

        @Test
        @DisplayName("READER_IDLE closes via ctx and does not call fireUserEventTriggered")
        void directReaderIdle() throws Exception {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            io.netty.channel.Channel ch = mock(io.netty.channel.Channel.class);
            when(ctx.channel()).thenReturn(ch);
            when(ch.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 1));

            IdleConnectionReaperHandler.INSTANCE.userEventTriggered(
                    ctx, IdleStateEvent.READER_IDLE_STATE_EVENT);

            verify(ctx, times(1)).close();
            verify(ctx, never()).fireUserEventTriggered(any());
        }

        @Test
        @DisplayName("ALL_IDLE closes via ctx and does not propagate")
        void directAllIdle() throws Exception {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            io.netty.channel.Channel ch = mock(io.netty.channel.Channel.class);
            when(ctx.channel()).thenReturn(ch);
            when(ch.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 1));

            IdleConnectionReaperHandler.INSTANCE.userEventTriggered(
                    ctx, IdleStateEvent.ALL_IDLE_STATE_EVENT);

            verify(ctx, times(1)).close();
            verify(ctx, never()).fireUserEventTriggered(any());
        }

        @Test
        @DisplayName("WRITER_IDLE forwards via ctx and does not close")
        void directWriterIdle() throws Exception {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

            IdleConnectionReaperHandler.INSTANCE.userEventTriggered(
                    ctx, IdleStateEvent.WRITER_IDLE_STATE_EVENT);

            verify(ctx, never()).close();
            verify(ctx, times(1))
                    .fireUserEventTriggered(same(IdleStateEvent.WRITER_IDLE_STATE_EVENT));
        }

        @Test
        @DisplayName("non-idle event is forwarded verbatim")
        void directNonIdle() throws Exception {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            Object evt = new Object();

            IdleConnectionReaperHandler.INSTANCE.userEventTriggered(ctx, evt);

            verify(ctx, never()).close();
            verify(ctx, times(1)).fireUserEventTriggered(same(evt));
        }
    }
}
