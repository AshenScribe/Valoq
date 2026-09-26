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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.LineEncoder;
import io.netty.handler.codec.string.LineSeparator;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.util.CharsetUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PublicKeyClient {

    private final String authServerHost;
    private final int authServerPort;

    public PublicKeyClient(String authServerHost, int authServerPort) {
        this.authServerHost = authServerHost;
        this.authServerPort = authServerPort;
    }

    public String getPublicKey() throws Exception {
        EventLoopGroup group =
                new MultiThreadIoEventLoopGroup(
                        Epoll.isAvailable()
                                ? EpollIoHandler.newFactory()
                                : NioIoHandler.newFactory());
        CompletableFuture<String> futureResponse = new CompletableFuture<>();

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(
                            Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(
                            new ChannelInitializer<SocketChannel>() {
                                @Override
                                protected void initChannel(SocketChannel ch) {
                                    ChannelPipeline pipeline = ch.pipeline();
                                    pipeline.addLast(
                                            new LineEncoder(LineSeparator.UNIX, CharsetUtil.UTF_8));
                                    pipeline.addLast(new LineBasedFrameDecoder(1024));
                                    pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
                                    pipeline.addLast(new PublicKeyClientHandler(futureResponse));
                                }
                            });

            Channel channel = b.connect(authServerHost, authServerPort).sync().channel();
            channel.writeAndFlush("PUBLIC_KEY");
            return futureResponse.get(5, TimeUnit.SECONDS);

        } finally {
            group.shutdownGracefully();
        }
    }

    private static class PublicKeyClientHandler extends SimpleChannelInboundHandler<String> {
        private final CompletableFuture<String> futureResponse;

        public PublicKeyClientHandler(CompletableFuture<String> futureResponse) {
            this.futureResponse = futureResponse;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            futureResponse.complete(msg);
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            futureResponse.completeExceptionally(cause);
            ctx.close();
        }
    }
}
