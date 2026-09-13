package server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import server.ConnectionTracker;
import server.MessageServer;
import server.Session;

public class ConnectionLifecycleHandler extends ChannelInboundHandlerAdapter {

    private final ConnectionTracker connectionTracker;

    public ConnectionLifecycleHandler(ConnectionTracker connectionTracker) {
        this.connectionTracker = connectionTracker;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        Session session = ctx.channel()
                .attr(MessageServer.MessageServerInitializer.SESSION_KEY)
                .get();

        if (session != null && session.getUserId() != null) {
            connectionTracker.unregister(session.getUserId(), ctx.channel());
        }

        ctx.fireChannelInactive();
    }
}
