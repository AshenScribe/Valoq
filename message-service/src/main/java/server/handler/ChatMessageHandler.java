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
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.MessageRouter;
import server.MessageServer;
import server.Session;
import server.model.Event;
import server.model.EventType;

/**
 * Handler for processing chat messages sent by clients. It listens for messages in the format "SEND
 * <recipientId> <client_created_at> <payload></payload>" and routes them to the appropriate
 * recipients using the MessageRouter.
 */
public class ChatMessageHandler extends SimpleChannelInboundHandler<String> {

    private static final Pattern SEND_PATTERN =
            Pattern.compile("^SEND ([^\\s]{1,128}) ([^\\s]+) ([^\\s]+) ([^\\s]+)$");
    private final MessageRouter messageRouter;

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

        String senderId = session.getUserId();
        String conversationId = matcher.group(1);
        String clientMessageId = matcher.group(2);
        String timestamp = matcher.group(3);
        String payload = matcher.group(4);
        Event event =
                new Event(
                        senderId,
                        conversationId,
                        clientMessageId,
                        EventType.MESSAGE_CREATED,
                        payload,
                        Instant.parse(timestamp));
        messageRouter.route(event);
    }
}
