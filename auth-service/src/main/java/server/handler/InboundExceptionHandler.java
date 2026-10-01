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

import exception.UserNotFoundException;
import exception.ValidationException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tail pipeline handler that converts uncaught inbound exceptions into protocol-compliant ERROR
 * responses, following the exception-routing pattern from Apache Cassandra's transport layer.
 */
@ChannelHandler.Sharable
public final class InboundExceptionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboundExceptionHandler.class);
    public static final InboundExceptionHandler INSTANCE = new InboundExceptionHandler();

    private InboundExceptionHandler() {}

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ctx.channel().isOpen()) {
            return;
        }

        Throwable root = unwrap(cause);
        if (root instanceof IOException) {
            LOGGER.debug(
                    "Client abruptly closed connection: {} ({})",
                    ctx.channel().remoteAddress(),
                    root.getMessage());
            ctx.close();
            return;
        }
        String clientErrorMessage = toClientErrorMessage(root);
        boolean isFatal = isFatal(root);

        if (isFatal) {
            LOGGER.warn(
                    "Fatal channel error on {}: {}",
                    ctx.channel().remoteAddress(),
                    root.getMessage());
        } else if (isClientFault(root)) {
            LOGGER.debug(
                    "Client command error on {}: {}",
                    ctx.channel().remoteAddress(),
                    root.getMessage());
        } else {
            LOGGER.error("Unexpected server error on {}", ctx.channel().remoteAddress(), root);
        }
        ChannelFuture writeFuture = ctx.writeAndFlush("ERROR " + clientErrorMessage);
        if (isFatal) {
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** Unwraps wrapper exceptions (Netty DecoderException, CompletableFuture wrappers). */
    private Throwable unwrap(Throwable cause) {
        Throwable current = cause;
        while (current.getCause() != null
                && (current instanceof DecoderException
                        || current instanceof CompletionException
                        || current instanceof ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    /** Determines whether the channel's stream state is compromised and must be terminated. */
    private boolean isFatal(Throwable t) {
        return t instanceof TooLongFrameException
                || t instanceof CorruptedFrameException
                || t instanceof OutOfMemoryError;
    }

    /**
     * Determines whether the failure was caused by invalid client input rather than a server fault.
     */
    private boolean isClientFault(Throwable t) {
        return t instanceof ValidationException
                || t instanceof UserNotFoundException
                || t instanceof IllegalArgumentException
                || t instanceof DecoderException;
    }

    /** Produces a clean, safe protocol error message string without exposing stack traces. */
    private String toClientErrorMessage(Throwable t) {
        if (t instanceof TooLongFrameException) {
            return "Command exceeds maximum allowed frame length";
        }
        if (t instanceof CorruptedFrameException) {
            return "Corrupted frame received: " + t.getMessage();
        }
        if (isClientFault(t)) {
            String msg = t.getMessage();
            return (msg != null && !msg.isBlank()) ? msg : t.getClass().getSimpleName();
        }
        return "Internal server error";
    }
}
