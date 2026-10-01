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

import com.fasterxml.jackson.databind.JsonNode;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jwt.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ClientConnection;
import server.ConnectionTracker;
import server.MessageRouter;
import server.model.Event;
import server.model.EventType;
import server.util.RateLimitedLogger;
import service.GetOutOfSyncEvents;

public class CommandDispatcherHandler extends SimpleChannelInboundHandler<String> {


    private static final Logger LOGGER = LoggerFactory.getLogger(CommandDispatcherHandler.class);
    private static final RateLimitedLogger RATE_LIMITED_LOGGER = RateLimitedLogger.getLogger(LOGGER, 5, TimeUnit.SECONDS);

    private static final Pattern SEND_PATTERN =
            Pattern.compile("^SEND\\s+([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)$");
    private static final Pattern INIT_PATTERN = Pattern.compile("^INIT\\s+(.+)$");
    private CompletableFuture<?> lastRouteFuture = CompletableFuture.completedFuture(null);

    private final ConnectionTracker connectionTracker;
    private final MessageRouter messageRouter;
    private final UserEventRepository userEventRepository;
    private final EventRepository eventRepository;

    public CommandDispatcherHandler(
            ConnectionTracker connectionTracker, MessageRouter messageRouter) {
        this.connectionTracker = connectionTracker;
        this.messageRouter = messageRouter;
        this.userEventRepository = new UserEventRepository();
        this.eventRepository = new EventRepository();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String rawMsg) {
        String msg = rawMsg.trim();
        if (msg.isEmpty()) {
            RATE_LIMITED_LOGGER.warn("empty_cmd", "Received empty command from {}", ctx.channel().remoteAddress());
            ctx.writeAndFlush("INVALID").addListener(ChannelFutureListener.CLOSE);
            return;
        }

        ClientConnection conn = ClientConnection.get(ctx.channel());
        if (conn == null) {
            conn = connectionTracker.track(ctx.channel());
        }

        if (msg.startsWith("INIT")) {
            handleInit(ctx, conn, msg);
        } else if (msg.startsWith("SEND")) {
            handleSend(ctx, conn, msg);
        } else if (msg.startsWith("SYNC")) {
            handleSync(ctx, conn, msg);
        } else {
            ctx.writeAndFlush("INVALID").addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void handleInit(ChannelHandlerContext ctx, ClientConnection conn, String msg) {
        Matcher matcher = INIT_PATTERN.matcher(msg);
        if (!matcher.matches()) {
            ctx.writeAndFlush("INVALID").addListener(ChannelFutureListener.CLOSE);
            return;
        }

        try {
            String token = matcher.group(1).trim();
            JsonNode payload = JwtUtil.getInstance().decodeToPayload(token);

            String rawUserId = null;
            if (payload.has("sub")) {
                rawUserId = payload.get("sub").asText();
            } else if (payload.has("userId")) {
                rawUserId = payload.get("userId").asText();
            }

            if (rawUserId == null) {
                ctx.writeAndFlush("INVALID").addListener(ChannelFutureListener.CLOSE);
                return;
            }

            UUID userId = UUID.fromString(rawUserId);

            // Register into connectionTracker and mark ClientConnection as authenticated
            connectionTracker.register(userId, ctx.channel());
            ctx.writeAndFlush("SUCCESS");

        } catch (Exception e) {
            ctx.writeAndFlush("INVALID").addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void handleSend(ChannelHandlerContext ctx, ClientConnection conn, String msg) {
        if (!conn.isAuthenticated()) {
            ctx.writeAndFlush("ERROR Session not initialized");
            return;
        }

        Matcher matcher = SEND_PATTERN.matcher(msg);
        if (!matcher.matches()) {
            ctx.writeAndFlush("ERROR Invalid SEND format");
            return;
        }

        try {
            UUID conversationId = UUID.fromString(matcher.group(1));
            Instant timestamp = Instant.parse(matcher.group(2));
            String payload = matcher.group(3);

            Event event =
                    new Event(
                            conn.getUserId(),
                            conversationId,
                            UUID.randomUUID(),
                            EventType.MESSAGE_CREATED,
                            payload,
                            timestamp);
            lastRouteFuture =
                    lastRouteFuture
                            .handle((_, _) -> null)
                            .thenCompose(v -> messageRouter.route(event));

        } catch (Exception e) {
            ctx.writeAndFlush("ERROR Invalid SEND format");
        }
    }

    private void handleSync(ChannelHandlerContext ctx, ClientConnection conn, String msg) {
        if (!conn.isAuthenticated()) {
            ctx.writeAndFlush("ERROR: Session not initialized\n");
            return;
        }

        String eventIdParam = msg.substring(4).trim();
        if (eventIdParam.isEmpty()) {
            ctx.writeAndFlush("ERROR: SYNC failed\n");
            return;
        }

        GetOutOfSyncEvents getOutOfSyncEvents =
                new GetOutOfSyncEvents(
                        conn.getUserId(), eventIdParam, userEventRepository, eventRepository);

        getOutOfSyncEvents
                .serve()
                .thenAccept(
                        events -> {
                            for (Event event : events) {
                                String response =
                                        String.format(
                                                "EVENT %s %s %s %s %s\n",
                                                event.eventType().name(),
                                                event.eventId(),
                                                event.conversationId(),
                                                event.senderId(),
                                                event.payload());
                                ctx.write(response);
                            }
                            ctx.flush();
                        })
                .exceptionally(
                        error -> {
                            ctx.writeAndFlush("ERROR: SYNC failed\n");
                            return null;
                        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        RATE_LIMITED_LOGGER.warn("exception_caught", "Exception caught in CommandDispatcherHandler: {}", cause.getMessage());
        ctx.close();
    }
}
