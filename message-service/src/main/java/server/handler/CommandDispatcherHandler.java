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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import jwt.JwtUtil;
import protocol.BinaryMessages;
import protocol.Envelope;
import protocol.Opcode;
import server.ClientConnection;
import server.ConnectionTracker;
import server.MessageRouter;
import server.model.Event;
import server.model.EventType;
import service.GetOutOfSyncEvents;

public class CommandDispatcherHandler extends SimpleChannelInboundHandler<Envelope> {

    private static final int HIGH_WATERMARK = 64;
    private static final int LOW_WATERMARK = 32;

    private final ConnectionTracker connectionTracker;
    private final MessageRouter messageRouter;
    private final UserEventRepository userEventRepository;
    private final EventRepository eventRepository;

    private final AtomicInteger inFlightCount = new AtomicInteger(0);
    private CompletableFuture<?> lastRouteFuture = CompletableFuture.completedFuture(null);

    public CommandDispatcherHandler(
            ConnectionTracker connectionTracker, MessageRouter messageRouter) {
        this.connectionTracker = connectionTracker;
        this.messageRouter = messageRouter;
        this.userEventRepository = new UserEventRepository();
        this.eventRepository = new EventRepository();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Envelope envelope) {
        ClientConnection conn = ClientConnection.get(ctx.channel());
        if (conn == null) {
            conn = connectionTracker.track(ctx.channel());
        }

        int streamId = envelope.getHeader().streamId();
        Opcode opcode = envelope.getHeader().opcode();

        switch (opcode) {
            case INIT -> handleInit(ctx, conn, envelope, streamId);
            case SEND -> handleSend(ctx, conn, envelope, streamId);
            case SYNC -> handleSync(ctx, conn, envelope, streamId);
            default -> {
                Envelope err =
                        BinaryMessages.createErrorResponse(
                                streamId, "Unexpected opcode: " + opcode, ctx.alloc());
                ctx.writeAndFlush(err).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private void handleInit(
            ChannelHandlerContext ctx, ClientConnection conn, Envelope env, int streamId) {
        try {
            BinaryMessages.InitRequest req = BinaryMessages.InitRequest.decode(env.getBody());
            JsonNode payload = JwtUtil.getInstance().decodeToPayload(req.token());

            String rawUserId = null;
            if (payload.has("sub")) {
                rawUserId = payload.get("sub").asText();
            } else if (payload.has("userId")) {
                rawUserId = payload.get("userId").asText();
            }

            if (rawUserId == null) {
                Envelope err =
                        BinaryMessages.createErrorResponse(
                                streamId, "Missing userId claim", ctx.alloc());
                ctx.writeAndFlush(err).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            UUID userId = UUID.fromString(rawUserId);
            connectionTracker.register(userId, ctx.channel());
            conn.authenticate(userId);

            // Send READY response stamped with client's streamId
            ctx.writeAndFlush(BinaryMessages.createReadyResponse(streamId));

        } catch (Exception e) {
            Envelope err = BinaryMessages.createErrorResponse(streamId, "INVALID", ctx.alloc());
            ctx.writeAndFlush(err).addListener(ChannelFutureListener.CLOSE);
        } finally {
            env.release();
        }
    }

    private void handleSend(
            ChannelHandlerContext ctx, ClientConnection conn, Envelope env, int streamId) {
        if (!conn.isAuthenticated()) {
            Envelope err =
                    BinaryMessages.createErrorResponse(
                            streamId, "ERROR Session not initialized", ctx.alloc());
            ctx.writeAndFlush(err);
            env.release();
            return;
        }

        try {
            BinaryMessages.SendRequest req = BinaryMessages.SendRequest.decode(env.getBody());

            Event event =
                    new Event(
                            conn.getUserId(),
                            req.conversationId(),
                            UUID.randomUUID(),
                            EventType.MESSAGE_CREATED,
                            req.payload(),
                            req.timestamp());

            int currentInFlight = inFlightCount.incrementAndGet();
            if (currentInFlight >= HIGH_WATERMARK) {
                ChannelConfig config = ctx.channel().config();
                if (config.isAutoRead()) {
                    config.setAutoRead(false);
                }
            }

            lastRouteFuture =
                    lastRouteFuture
                            .handle((res, ex) -> null)
                            .thenCompose(v -> messageRouter.route(event))
                            .whenComplete(
                                    (res, ex) -> {
                                        int remaining = inFlightCount.decrementAndGet();
                                        if (remaining <= LOW_WATERMARK) {
                                            ChannelConfig config = ctx.channel().config();
                                            if (!config.isAutoRead()) {
                                                config.setAutoRead(true);
                                            }
                                        }
                                    });

        } catch (Exception e) {
            Envelope err =
                    BinaryMessages.createErrorResponse(
                            streamId, "ERROR Invalid SEND format", ctx.alloc());
            ctx.writeAndFlush(err);
        } finally {
            env.release();
        }
    }

    private void handleSync(
            ChannelHandlerContext ctx, ClientConnection conn, Envelope env, int streamId) {
        if (!conn.isAuthenticated()) {
            Envelope err =
                    BinaryMessages.createErrorResponse(
                            streamId, "ERROR Session not initialized", ctx.alloc());
            ctx.writeAndFlush(err);
            env.release();
            return;
        }

        try {
            BinaryMessages.SyncRequest req = BinaryMessages.SyncRequest.decode(env.getBody());

            GetOutOfSyncEvents getOutOfSyncEvents =
                    new GetOutOfSyncEvents(
                            conn.getUserId(),
                            req.cursorEventId().toString(),
                            userEventRepository,
                            eventRepository);

            getOutOfSyncEvents
                    .serve()
                    .thenAccept(
                            events -> {
                                for (Event event : events) {
                                    Envelope eventEnv =
                                            BinaryMessages.createEventEnvelope(
                                                    event, streamId, ctx.alloc());
                                    ctx.write(eventEnv);
                                }
                                ctx.flush();
                            })
                    .exceptionally(
                            error -> {
                                Envelope err =
                                        BinaryMessages.createErrorResponse(
                                                streamId, "ERROR SYNC failed", ctx.alloc());
                                ctx.writeAndFlush(err);
                                return null;
                            });

        } catch (Exception e) {
            Envelope err =
                    BinaryMessages.createErrorResponse(
                            streamId, "ERROR Invalid SYNC format", ctx.alloc());
            ctx.writeAndFlush(err);
        } finally {
            env.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
