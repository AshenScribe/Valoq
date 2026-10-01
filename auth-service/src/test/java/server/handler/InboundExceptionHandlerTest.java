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
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InboundExceptionHandlerTest {

    @Test
    void testClientErrorWritesErrorAndKeepsChannelOpen() {
        EmbeddedChannel channel = new EmbeddedChannel(InboundExceptionHandler.INSTANCE);
        channel.pipeline().fireExceptionCaught(new DecoderException("Invalid command format"));

        String response = channel.readOutbound();
        Assertions.assertEquals("ERROR Invalid command format", response);
        Assertions.assertTrue(
                channel.isOpen(), "Channel should stay open on non-fatal client errors");
    }

    @Test
    void testFatalFrameOverflowWritesErrorAndClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(InboundExceptionHandler.INSTANCE);

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("Frame exceeds limit"));

        String response = channel.readOutbound();
        channel.runPendingTasks();

        Assertions.assertEquals("ERROR Command exceeds maximum allowed frame length", response);
        Assertions.assertFalse(channel.isOpen(), "Channel must close on fatal framing error");
    }

    @Test
    void testUnexpectedServerErrorMasksInternals() {
        EmbeddedChannel channel = new EmbeddedChannel(InboundExceptionHandler.INSTANCE);

        channel.pipeline().fireExceptionCaught(new NullPointerException("secret DB pointer null"));

        String response = channel.readOutbound();
        Assertions.assertEquals("ERROR Internal server error", response);
    }
}
