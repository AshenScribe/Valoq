package server.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes idle client channels when no inbound traffic has been received for a configured timeout,
 * preventing zombie sockets and file descriptor exhaustion. (Inspired by Cassandra's IDLE_STATE_HANDLER).
 */
@ChannelHandler.Sharable
public class IdleConnectionReaperHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdleConnectionReaperHandler.class);
    public static final IdleConnectionReaperHandler INSTANCE = new IdleConnectionReaperHandler();

    private IdleConnectionReaperHandler() {}

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.READER_IDLE || idleEvent.state() == IdleState.ALL_IDLE) {
                LOGGER.info(
                        "Closing idle connection from {} after timeout",
                        ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
        }
        ctx.fireUserEventTriggered(evt);
    }
}