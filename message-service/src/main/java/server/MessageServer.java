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
package server;

import config.ServerConfig;
import database.CassandraManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
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
import io.netty.handler.codec.string.LineEncoder;
import io.netty.handler.codec.string.LineSeparator;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import server.handler.ConnectionLifecycleHandler;
import server.handler.InitVerbHandler;

public class MessageServer {

    private final int port;
    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private final ConnectionTracker CONNECTION_TRACKER = new ConnectionTracker();

    private final IoHandlerFactory factory =
            Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();

    private final Class<? extends ServerChannel> channelClass =
            Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

    public MessageServer(ServerConfig serverConfig) {
        this.port = serverConfig.getServer().getPort();
    }

    public synchronized void start() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, factory);
        workerGroup = new MultiThreadIoEventLoopGroup(factory);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(channelClass)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new MessageServerInitializer(CONNECTION_TRACKER));

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
        CassandraManager.close();
    }

    ConnectionTracker getConnectionTracker() {
        return CONNECTION_TRACKER;
    }

    public int getPort() {
        return ((java.net.InetSocketAddress) channel.localAddress()).getPort();
    }

    public static class MessageServerInitializer extends ChannelInitializer<Channel> {

        public static final AttributeKey<Session> SESSION_KEY = AttributeKey.newInstance("session");
        private final ConnectionTracker connectionTracker;
        private final MessageRouter messageRouter;

        public MessageServerInitializer(ConnectionTracker connectionTracker) {
            this(
                    connectionTracker,
                    new MessageRouter(
                            connectionTracker,
                            CassandraManager.isInitialized() ? new database.MessageRepository() : null));
        }

        public MessageServerInitializer(ConnectionTracker connectionTracker, MessageRouter messageRouter) {
            this.connectionTracker = connectionTracker;
            this.messageRouter = messageRouter;
        }

        @Override
        protected void initChannel(Channel ch) {
            ch.attr(SESSION_KEY).set(new Session());
            ch.pipeline().addLast("lineEncoder", new LineEncoder(LineSeparator.UNIX, StandardCharsets.UTF_8));
            ch.pipeline().addLast("lineBasedFrameDecoder", new LineBasedFrameDecoder(1024));
            ch.pipeline().addLast("stringDecoder", new StringDecoder(StandardCharsets.UTF_8));
            ch.pipeline().addLast("logger", new LoggingHandler());
            ch.pipeline().addLast("initVerbHandler", new InitVerbHandler(connectionTracker, messageRouter));
            ch.pipeline().addLast("connectionLifecycleHandler", new ConnectionLifecycleHandler(connectionTracker));
        }
    }
}
