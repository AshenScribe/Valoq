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
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.UUID;
import server.MessageServer;
import server.Session;
import server.model.Event;
import service.GetOutOfSyncEvents;

public class SyncHandler extends SimpleChannelInboundHandler<String> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {

        if (!msg.startsWith("SYNC")) {
            ctx.fireChannelRead(msg);
            return;
        }

        Session session =
                ctx.channel().attr(MessageServer.MessageServerInitializer.SESSION_KEY).get();

        if (session == null) {
            ctx.writeAndFlush("ERROR: Session not initialized\n");
            return;
        }

        UUID userId = session.getUserId();
        String eventId = msg.substring(5).trim();

        GetOutOfSyncEvents getOutOfSyncEvents = new GetOutOfSyncEvents(userId, eventId);

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
}
