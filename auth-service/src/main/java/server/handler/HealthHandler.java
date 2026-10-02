package server.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

@ChannelHandler.Sharable
public final class HealthHandler extends SimpleChannelInboundHandler<String> {

    public static final HealthHandler INSTANCE = new HealthHandler();

    private HealthHandler() {}

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        if ("PING".equalsIgnoreCase(msg.trim()) || "HEALTH".equalsIgnoreCase(msg.trim())) {
            ctx.writeAndFlush("OK");
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}