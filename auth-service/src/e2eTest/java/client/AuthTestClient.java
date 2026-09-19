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
package client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AuthTestClient implements AutoCloseable {

    private final EventLoopGroup group;
    private final Channel channel;
    private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();

    public AuthTestClient(String host, int port) throws InterruptedException {
        this.group = new NioEventLoopGroup(1);

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LineBasedFrameDecoder(1024));
                        ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                responses.offer(msg);
                            }
                        });
                    }
                });

        this.channel = b.connect(host, port).sync().channel();
    }

    public String send(String command) {
        try {
            responses.clear();
            channel.writeAndFlush(command.endsWith("\n") ? command : command + "\n")
                    .sync();
            String response = responses.poll(5, TimeUnit.SECONDS);
            if (response == null) {
                throw new IllegalStateException("Timeout waiting for server response");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public String getPublicKey() {
        return send("PUBLIC_KEY");
    }

    public String loginBasic(String username, String passwordHash, String salt) {
        return send(String.format("AUTH BASIC %s:%s:%s", username, passwordHash, salt));
    }

    public String loginToken(String token) {
        return send("AUTH TOKEN " + token);
    }

    @Override
    public void close() {
        if (channel != null) channel.close();
        group.shutdownGracefully();
    }

    public String register(String username, String password, String salt, String email) {
        String json = String.format(
                "{\"username\":\"%s\",\"password\":\"%s\",\"salt\":\"%s\",\"email\":\"%s\"}",
                username, password, salt, email);
        String base64Payload =
                java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return send("AUTH REGISTER " + base64Payload);
    }
}
