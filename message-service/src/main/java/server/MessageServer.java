package server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import server.handler.InitVerbHandler;

public class MessageServer {

    private final int port;
    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private static final ConnectionTracker CONNECTION_TRACKER = new ConnectionTracker();

    private final IoHandlerFactory factory =
            Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();

    private final Class<? extends ServerChannel> channelClass =
            Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

    public MessageServer(int port) {
        this.port = port;
    }

    public synchronized void start() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, factory);
        workerGroup = new MultiThreadIoEventLoopGroup(factory);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(channelClass)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new MessageServerInitializer());

        channel = bootstrap.bind(port).sync().channel();
    }

    public synchronized void stop() {
        if (channel != null) {
            channel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static class MessageServerInitializer extends ChannelInitializer<Channel> {

        public static final AttributeKey<Session> SESSION_KEY = AttributeKey.newInstance("session");

        public MessageServerInitializer() {
            super();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Session session = ctx.channel().attr(SESSION_KEY).get();

            if (session != null && session.getUserId() != null)
                CONNECTION_TRACKER.unregister(session.getUserId(), ctx.channel());

            ctx.fireChannelInactive();
        }

        @Override
        protected void initChannel(Channel ch) {
            ch.attr(SESSION_KEY).set(new Session());
            ch.pipeline()
                    .addLast("stringEncoder", new io.netty.handler.codec.string.StringEncoder(StandardCharsets.UTF_8));
            ch.pipeline().addLast("lineBasedFrameDecoder", new LineBasedFrameDecoder(1024));
            ch.pipeline().addLast("stringDecoder", new StringDecoder(StandardCharsets.UTF_8));
            ch.pipeline().addLast("logger", new LoggingHandler());
            ch.pipeline().addLast("initVerbHandler", new InitVerbHandler(CONNECTION_TRACKER));
        }
    }
}
