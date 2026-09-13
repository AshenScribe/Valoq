package server.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.ConnectionTracker;
import server.MessageServer;
import server.Session;

public class InitVerbHandler extends SimpleChannelInboundHandler<String> {

    private static final Pattern INIT_PATTERN = Pattern.compile("^(INIT) +(\\S+)$");

    private final ConnectionTracker connectionTracker;

    public InitVerbHandler(ConnectionTracker connectionTracker) {
        this.connectionTracker = connectionTracker;
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
            ctx.writeAndFlush("SUCCESS\n");
            connectionTracker.register(userId, ctx.channel());
            ctx.pipeline().replace(this, "chatMessageHandler", new ChatMessageHandler(connectionTracker));
        } else {
            ctx.writeAndFlush("INVALID\n").addListener(ChannelFutureListener.CLOSE);
        }
    }
}
