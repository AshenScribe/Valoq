package server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.MessageRouter;
import server.MessageServer;
import server.Session;

public class ChatMessageHandler extends SimpleChannelInboundHandler<String> {

    private static final Pattern SEND_PATTERN = Pattern.compile("^SEND ([^\\s]{1,64}) ([^\\s]+)$");
    private final MessageRouter messageRouter;

    public ChatMessageHandler(MessageRouter messageRouter) {
        this.messageRouter = messageRouter;
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

        boolean delivered = messageRouter.route(session.getUserId(), matcher.group(1), matcher.group(2));

        if (!delivered) {
            ctx.writeAndFlush("ERROR Recipient not connected\n");
        }
    }
}
