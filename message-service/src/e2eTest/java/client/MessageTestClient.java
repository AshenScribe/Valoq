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
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MessageTestClient implements AutoCloseable {

    private final EventLoopGroup group;
    private final Channel channel;
    private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
    private final java.security.PrivateKey privateKey;

    public MessageTestClient(String host, int port, java.security.PrivateKey privateKey)
            throws InterruptedException {
        this.privateKey = privateKey;
        this.group =
                new MultiThreadIoEventLoopGroup(
                        Epoll.isAvailable()
                                ? EpollIoHandler.newFactory()
                                : NioIoHandler.newFactory());

        Bootstrap b =
                new Bootstrap()
                        .group(group)
                        .channel(
                                Epoll.isAvailable()
                                        ? EpollSocketChannel.class
                                        : NioSocketChannel.class)
                        .handler(
                                new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel ch) {
                                        ch.pipeline().addLast(new LineBasedFrameDecoder(1024));
                                        ch.pipeline()
                                                .addLast(new StringDecoder(StandardCharsets.UTF_8));
                                        ch.pipeline()
                                                .addLast(new StringEncoder(StandardCharsets.UTF_8));
                                        ch.pipeline()
                                                .addLast(
                                                        new SimpleChannelInboundHandler<String>() {
                                                            @Override
                                                            protected void channelRead0(
                                                                    ChannelHandlerContext ctx,
                                                                    String msg) {
                                                                responses.offer(msg);
                                                            }
                                                        });
                                    }
                                });

        this.channel = b.connect(host, port).sync().channel();
    }

    public String init(String userId) {
        String token;
        try {
            token = createToken(userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        send("INIT " + token);
        return readLine();
    }

    public String createToken(String userId) throws Exception {
        String header =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        String payload =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));

        String contentToSign = header + "." + payload;

        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(contentToSign.getBytes(StandardCharsets.UTF_8));

        String sigBase64 =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return contentToSign + "." + sigBase64;
    }

    public void sendTo(String recipientId, String base64Payload) {
        send(String.format("SEND %s %s", recipientId, base64Payload));
    }

    public String readLine() {
        return readLine(Duration.ofSeconds(2));
    }

    public String readLine(Duration timeout) {
        try {
            return responses.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public void send(String command) {
        try {
            channel.writeAndFlush(command.endsWith("\n") ? command : command + "\n").sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public boolean isClosedByServer() {
        try {
            return channel.closeFuture().await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        channel.close();
        group.shutdownGracefully();
    }
}
