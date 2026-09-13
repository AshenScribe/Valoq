package server.handler;

import authenticator.jwt.KeyProvider;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Base64;

public class PublicKeyHandler extends SimpleChannelInboundHandler<String> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        if (msg.equals("PUBLIC_KEY"))
            ctx.writeAndFlush(Base64.getEncoder()
                            .encodeToString(
                                    KeyProvider.getInstance().getPublicKey().getEncoded()) + "\n");
        else ctx.fireChannelRead(msg);
    }
}
