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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IdleConnectionReaperHandler Unit Tests")
class IdleConnectionReaperHandlerTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE);
    }

    @AfterEach
    void tearDown() {
        if (channel.isOpen()) {
            channel.finishAndReleaseAll();
        }
    }

    private static final class EventCaptureHandler extends ChannelInboundHandlerAdapter {
        private Object capturedEvent = null;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            this.capturedEvent = evt;
            ctx.fireUserEventTriggered(evt);
        }

        Object getCapturedEvent() {
            return capturedEvent;
        }
    }

    @Nested
    @DisplayName("Idle State Reaping Rules")
    class ReaperRulesTests {

        @Test
        @DisplayName("READER_IDLE state event closes the connection channel")
        void readerIdleClosesChannel() {
            Assertions.assertTrue(channel.isOpen());

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);

            Assertions.assertFalse(channel.isOpen(), "Reader idle must close zombie sockets");
        }

        @Test
        @DisplayName("ALL_IDLE state event closes the connection channel")
        void allIdleClosesChannel() {
            Assertions.assertTrue(channel.isOpen());

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);

            Assertions.assertFalse(channel.isOpen(), "All idle must close zombie sockets");
        }

        @Test
        @DisplayName("WRITER_IDLE state event is passed through and does not close channel")
        void writerIdlePassesThrough() {
            EventCaptureHandler capture = new EventCaptureHandler();
            channel.pipeline().addLast(capture);

            Assertions.assertTrue(channel.isOpen());

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);

            Assertions.assertTrue(channel.isOpen(), "Writer idle should not trigger channel close");
            Assertions.assertEquals(
                    IdleStateEvent.WRITER_IDLE_STATE_EVENT, capture.getCapturedEvent());
        }

        @Test
        @DisplayName("non-idle user events are safely propagated down the pipeline")
        void nonIdleEventsPropagate() {
            EventCaptureHandler capture = new EventCaptureHandler();
            channel.pipeline().addLast(capture);

            String customEvent = "HEARTBEAT_PING";
            channel.pipeline().fireUserEventTriggered(customEvent);

            Assertions.assertTrue(channel.isOpen());
            Assertions.assertEquals(customEvent, capture.getCapturedEvent());
        }
    }
}
