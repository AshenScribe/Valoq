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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.LineEncoder;
import io.netty.handler.codec.string.LineSeparator;
import io.netty.handler.codec.string.StringDecoder;
import java.nio.charset.StandardCharsets;
import server.codec.CommandDecoder;
import server.handler.AuthenticationHandler;
import server.handler.PublicKeyHandler;

public class AuthServer {
    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel channelFuture;

    public AuthServer(ServerConfig serverConfig) {
        this.port = serverConfig.server().port();
        final IoHandlerFactory handler = Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        this.bossGroup = new MultiThreadIoEventLoopGroup(handler);
        this.workerGroup = new MultiThreadIoEventLoopGroup(handler);
    }

    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new AuthServerInitializer())
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        channelFuture = bootstrap.bind(port).sync().channel();
    }

    public int getPort() {
        if (channelFuture != null && channelFuture.localAddress() instanceof java.net.InetSocketAddress addr) {
            return addr.getPort();
        }
        return port;
    }

    public void stop() {
        if (channelFuture != null) {
            channelFuture.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private static final class AuthServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast("lineEncoder", new LineEncoder(LineSeparator.UNIX, StandardCharsets.UTF_8));
            ch.pipeline().addLast("lineBasedFrameDecoder1024", new LineBasedFrameDecoder(1024));
            ch.pipeline().addLast("stringDecoder", new StringDecoder(StandardCharsets.UTF_8));
            ch.pipeline().addLast("publicKeyHandler", new PublicKeyHandler());
            ch.pipeline().addLast("commandDecoder", new CommandDecoder());
            ch.pipeline().addLast("authenticationHandler", new AuthenticationHandler());
        }
    }
}
