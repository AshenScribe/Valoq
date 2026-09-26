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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicKeyClientTest {

    private static final String HOST = "localhost";
    private static final int PORT = 10000;
    private final PublicKeyClient publicKeyClient = new PublicKeyClient(HOST, PORT);

    @BeforeAll
    public static void setup() throws InterruptedException {
        final IoHandlerFactory handler =
                Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(handler);
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(handler);
        new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(
                        Epoll.isAvailable()
                                ? EpollServerSocketChannel.class
                                : NioServerSocketChannel.class)
                .childHandler(
                        new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast(
                                                "lineEncoder",
                                                new LineEncoder(
                                                        LineSeparator.UNIX,
                                                        StandardCharsets.UTF_8));
                                ch.pipeline()
                                        .addLast(
                                                "lineBasedFrameDecoder1024",
                                                new LineBasedFrameDecoder(1024));
                                ch.pipeline()
                                        .addLast(
                                                "stringDecoder",
                                                new StringDecoder(StandardCharsets.UTF_8));
                                ch.pipeline()
                                        .addLast(
                                                "mockHandler",
                                                new SimpleChannelInboundHandler<>() {
                                                    @Override
                                                    protected void channelRead0(
                                                            ChannelHandlerContext ctx, Object msg) {
                                                        if ("PUBLIC_KEY".equals(msg)) {
                                                            ctx.writeAndFlush("MOCK_PUBLIC_KEY");
                                                        } else {
                                                            ctx.writeAndFlush("UNKNOWN_COMMAND");
                                                        }
                                                    }
                                                });
                            }
                        })
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .bind(HOST, PORT)
                .sync();
    }

    @Test
    public void testGetPublicKey() throws Exception {
        String publicKey = publicKeyClient.getPublicKey();
        Assertions.assertEquals("MOCK_PUBLIC_KEY", publicKey);
    }
}
