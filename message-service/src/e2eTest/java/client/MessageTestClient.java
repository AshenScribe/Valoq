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
import io.netty.buffer.ByteBuf;
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
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import protocol.BinaryMessages;
import protocol.BufferUtil;
import protocol.Envelope;
import protocol.EnvelopeDecoder;
import protocol.EnvelopeEncoder;
import protocol.Opcode;
import server.model.Event;

public class MessageTestClient implements AutoCloseable {

    private final EventLoopGroup group;
    private final Channel channel;
    private final BlockingQueue<Envelope> responses = new LinkedBlockingQueue<>();
    private final PrivateKey privateKey;
    private final AtomicInteger streamSequence = new AtomicInteger(0);

    public MessageTestClient(String host, int port, PrivateKey privateKey)
            throws InterruptedException {

        this.privateKey = privateKey;
        this.group =
                new MultiThreadIoEventLoopGroup(
                        Epoll.isAvailable()
                                ? EpollIoHandler.newFactory()
                                : NioIoHandler.newFactory());

        Bootstrap bootstrap =
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
                                        ch.pipeline().addLast("encoder", EnvelopeEncoder.INSTANCE);
                                        ch.pipeline()
                                                .addLast(
                                                        "decoder",
                                                        new EnvelopeDecoder(10 * 1024 * 1024));
                                        ch.pipeline()
                                                .addLast(
                                                        new SimpleChannelInboundHandler<
                                                                Envelope>() {
                                                            @Override
                                                            protected void channelRead0(
                                                                    ChannelHandlerContext ctx,
                                                                    Envelope msg) {
                                                                responses.offer(msg);
                                                            }
                                                        });
                                    }
                                });

        this.channel = bootstrap.connect(host, port).sync().channel();
    }

    public String init(UUID userId) {
        try {
            writeInit(userId);
            flush();

            Envelope response = readEnvelope();
            if (response.getHeader().opcode() == Opcode.READY) {
                response.release();
                return "SUCCESS";
            } else if (response.getHeader().opcode() == Opcode.ERROR) {
                String err = BufferUtil.readString(response.getBody());
                response.release();
                return err;
            }
            response.release();
            return "UNKNOWN";

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void sendMessage(UUID conversationId, String createdAt, String payload) {
        try {
            writeSendMessage(conversationId, createdAt, payload);
            flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void writeInit(UUID userId) throws Exception {
        writeInitToken(createToken(userId));
    }

    public void writeInitToken(String token) {
        int streamId = streamSequence.incrementAndGet();
        ByteBuf body = channel.alloc().buffer();
        BufferUtil.writeLongString(token, body);
        channel.write(Envelope.create(Opcode.INIT, streamId, body));
    }

    public void writeSendMessage(UUID conversationId, String createdAt, String payload) {
        int streamId = streamSequence.incrementAndGet();
        Instant instant = Instant.parse(createdAt);

        ByteBuf body = channel.alloc().buffer();
        BinaryMessages.SendRequest req =
                new BinaryMessages.SendRequest(conversationId, instant, payload);
        BinaryMessages.SendRequest.encode(req, body);
        channel.write(Envelope.create(Opcode.SEND, streamId, body));
    }

    public void sendInvalidInit(String token) {
        writeInitToken(token);
        flush();
    }

    public void sendMalformedSend() {
        int streamId = streamSequence.incrementAndGet();
        ByteBuf body = channel.alloc().buffer(0);
        channel.writeAndFlush(Envelope.create(Opcode.SEND, streamId, body));
    }

    public void flush() {
        channel.flush();
    }

    public Event readEvent() {
        return readEvent(Duration.ofSeconds(2));
    }

    public Event readEvent(Duration timeout) {
        Envelope env = readEnvelope(timeout);
        if (env == null) {
            return null;
        }
        try {
            if (env.getHeader().opcode() == Opcode.EVENT) {
                return BinaryMessages.decodeEvent(env.getBody());
            }
            return null;
        } finally {
            env.release();
        }
    }

    public Envelope readEnvelope() {
        return readEnvelope(Duration.ofSeconds(2));
    }

    public Envelope readEnvelope(Duration timeout) {
        try {
            return responses.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public String createToken(UUID userId) throws Exception {
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8));

        String payload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));

        String contentToSign = header + "." + payload;

        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(contentToSign.getBytes(StandardCharsets.UTF_8));

        String signatureBase64 =
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return contentToSign + "." + signatureBase64;
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
