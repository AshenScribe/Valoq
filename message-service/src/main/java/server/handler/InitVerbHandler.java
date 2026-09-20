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

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.ConnectionTracker;
import server.MessageRouter;
import server.MessageServer;
import server.Session;

public class InitVerbHandler extends SimpleChannelInboundHandler<String> {

    private static final Pattern INIT_PATTERN = Pattern.compile("^(INIT) +(\\S+)$");

    private final ConnectionTracker connectionTracker;
    private final MessageRouter messageRouter;

    public InitVerbHandler(ConnectionTracker connectionTracker) {
        this(connectionTracker, new MessageRouter(connectionTracker));
    }

    public InitVerbHandler(ConnectionTracker connectionTracker, MessageRouter messageRouter) {
        this.connectionTracker = connectionTracker;
        this.messageRouter = messageRouter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        final Matcher matcher = INIT_PATTERN.matcher(msg.trim());
        if (matcher.matches() && matcher.groupCount() == 2) {
            final Session session = ctx.channel()
                    .attr(MessageServer.MessageServerInitializer.SESSION_KEY)
                    .get();

            final String userId = matcher.group(2);
            session.setUserId(userId);
            ctx.writeAndFlush("SUCCESS");
            connectionTracker.register(userId, ctx.channel());
            ctx.pipeline().replace(this, "chatMessageHandler", new ChatMessageHandler(messageRouter));
        } else {
            ctx.writeAndFlush("INVALID").addListener(ChannelFutureListener.CLOSE);
        }
    }
}
