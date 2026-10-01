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

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.MessageRouter;
import server.MessageServer;
import server.Session;
import server.model.Event;
import server.model.EventType;

public class ChatMessageHandler extends SimpleChannelInboundHandler<String> {

    private static final Pattern SEND_PATTERN =
            Pattern.compile("^SEND ([^\\s]{1,128}) ([^\\s]+) ([^\\s]+)$");

    private static final int HIGH_WATERMARK = 64;
    private static final int LOW_WATERMARK = 32;

    private final MessageRouter messageRouter;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    private CompletableFuture<?> lastRouteFuture = CompletableFuture.completedFuture(null);

    public ChatMessageHandler(MessageRouter messageRouter) {
        this.messageRouter = messageRouter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        Matcher matcher = SEND_PATTERN.matcher(msg);
        if (!matcher.matches()) {
            ctx.writeAndFlush("ERROR Invalid SEND format");
            return;
        }

        Session session =
                ctx.channel().attr(MessageServer.MessageServerInitializer.SESSION_KEY).get();

        if (session == null || session.getUserId() == null) {
            ctx.writeAndFlush("ERROR Session not initialized");
            return;
        }

        UUID senderId = session.getUserId();
        UUID conversationId = UUID.fromString(matcher.group(1));
        String timestamp = matcher.group(2);
        String payload = matcher.group(3);

        Event event =
                new Event(
                        senderId,
                        conversationId,
                        UUID.randomUUID(),
                        EventType.MESSAGE_CREATED,
                        payload,
                        Instant.parse(timestamp));

        int currentInFlight = inFlightCount.incrementAndGet();
        if (currentInFlight >= HIGH_WATERMARK) {
            ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                config.setAutoRead(false);
            }
        }
        lastRouteFuture =
                lastRouteFuture
                        .handle((_, _) -> null)
                        .thenCompose(_ -> messageRouter.route(event))
                        .whenComplete(
                                (_, _) -> {
                                    int remaining = inFlightCount.decrementAndGet();
                                    if (remaining <= LOW_WATERMARK) {
                                        ChannelConfig config = ctx.channel().config();
                                        if (!config.isAutoRead()) {
                                            config.setAutoRead(true);
                                        }
                                    }
                                });
    }
}
