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

import cache.MessagePubSubListener;
import cache.RedisManager;
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
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.EnvelopeDecoder;
import protocol.EnvelopeEncoder;
import server.handler.CommandDispatcherHandler;
import server.handler.ConnectionLifecycleHandler;
import server.handler.IdleConnectionReaperHandler;

public class MessageServer {

    public static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageServer.class);

    private final int port;
    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private final ConnectionTracker connectionTracker;

    private final IoHandlerFactory factory =
            Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();

    private final Class<? extends ServerChannel> channelClass =
            Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

    public MessageServer(ServerConfig serverConfig) {
        this.port = serverConfig.serverPort();
        this.connectionTracker = new ConnectionTracker(serverConfig.nodeId());
    }

    public synchronized void start() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, factory);
        workerGroup = new MultiThreadIoEventLoopGroup(factory);

        ServerBootstrap bootstrap =
                new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(channelClass)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(new MessageServerInitializer(connectionTracker));
        RedisManager.getInstance().addPubSubListener(new MessagePubSubListener(connectionTracker));
        serverChannel = bootstrap.bind(port).sync().channel();
        LOGGER.info("MessageServer started on port {}", getPort());
    }

    public synchronized void stop() {
        LOGGER.info("Initiating graceful shutdown for MessageServer...");
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().syncUninterruptibly();
            LOGGER.info("Server port closed; no longer accepting new connections.");
        }

        connectionTracker.broadcastNotice("DISCONNECT Server shutting down");
        connectionTracker.closeAll();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(100, 2000, TimeUnit.MILLISECONDS).syncUninterruptibly();
            workerGroup.shutdownGracefully(100, 2000, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
        CassandraManager.close();
        RedisManager.getInstance().close();
        LOGGER.info("MessageServer stopped cleanly.");
    }

    public ConnectionTracker getConnectionTracker() {
        return connectionTracker;
    }

    public int getPort() {
        return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public static class MessageServerInitializer extends ChannelInitializer<Channel> {

        public static final AttributeKey<Session> SESSION_KEY = AttributeKey.valueOf("SESSION");

        private final ConnectionTracker connectionTracker;
        private final MessageRouter messageRouter;

        public MessageServerInitializer(ConnectionTracker connectionTracker) {
            this(connectionTracker, new MessageRouter(connectionTracker));
        }

        public MessageServerInitializer(
                ConnectionTracker connectionTracker, MessageRouter messageRouter) {
            this.connectionTracker = connectionTracker;
            this.messageRouter = messageRouter;
        }

        @Override
        protected void initChannel(Channel ch) {
            ch.attr(SESSION_KEY).set(new Session());
            connectionTracker.track(ch);

            // 1. Idle state reaper
            ch.pipeline()
                    .addLast("idleStateHandler", new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));
            ch.pipeline().addLast("idleReaperHandler", IdleConnectionReaperHandler.INSTANCE);

            // 2. Binary Framing & Codecs
            ch.pipeline().addLast("envelopeEncoder", EnvelopeEncoder.INSTANCE);
            ch.pipeline().addLast("envelopeDecoder", new EnvelopeDecoder(MAX_FRAME_LENGTH));

            // 3. Connection lifecycle and command dispatcher
            ch.pipeline()
                    .addLast(
                            "connectionLifecycleHandler",
                            new ConnectionLifecycleHandler(connectionTracker));
            ch.pipeline()
                    .addLast(
                            "commandDispatcher",
                            new CommandDispatcherHandler(connectionTracker, messageRouter));
        }
    }
}
