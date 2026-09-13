package server.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.ConnectionTracker;
import server.MessageServer;
import server.Session;

public class ChatMessageHandler extends SimpleChannelInboundHandler<String> {

    private static final Pattern SEND_PATTERN = Pattern.compile("^SEND ([^\\s]{1,64}) ([^\\s]+)$");

    private final ConnectionTracker connectionTracker;

    public ChatMessageHandler(ConnectionTracker connectionTracker) {
        this.connectionTracker = connectionTracker;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        final Matcher matcher = SEND_PATTERN.matcher(msg);
        if (!matcher.matches()) {
            ctx.writeAndFlush("ERROR Invalid SEND format\n");
            return;
        }
        final Session session = ctx.channel()
                .attr(MessageServer.MessageServerInitializer.SESSION_KEY)
                .get();
        if (session == null || session.getUserId() == null) {
            ctx.writeAndFlush("ERROR Session not initialized\n");
            return;
        }

        final String recipientId = matcher.group(1);
        final String base64Payload = matcher.group(2);
        final Channel recipientChannel = connectionTracker.get(recipientId);
        if (recipientChannel == null) {
            ctx.writeAndFlush("ERROR Recipient not connected\n");
            return;
        }

        recipientChannel.writeAndFlush(String.format("FROM %s %s\n", session.getUserId(), base64Payload));
    }
}
