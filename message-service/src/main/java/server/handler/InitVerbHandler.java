package server.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import server.MessageServer;
import server.Session;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InitVerbHandler extends SimpleChannelInboundHandler<String> {

	private static final Pattern INIT_PATTERN = Pattern.compile("^(INIT) +(\\S+)$");

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, String msg) {
		Matcher matcher = INIT_PATTERN.matcher(msg.trim());

		if (matcher.matches() && matcher.groupCount() == 2) {
			Session session = ctx.channel().attr(MessageServer.MessageServerInitializer.SESSION_KEY).get();

			if (session != null) {
				String userId = matcher.group(2);
				session.setUserId(userId);
				ctx.writeAndFlush("SUCCESS\n");
				ctx.pipeline().replace(this, "chatMessageHandler", new ChatMessageHandler());
			} else {
				throw new IllegalStateException("Session attribute not attached to Channel");
			}
		} else {
			ctx.writeAndFlush("INVALID\n").addListener(ChannelFutureListener.CLOSE);
		}
	}
}
