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
import java.util.List;
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

    // Cassandra SimpleClient pattern: separate server push events from synchronous request/response
    // envelopes
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
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
                                                                if (msg.getHeader().opcode()
                                                                        == Opcode.EVENT) {
                                                                    try {
                                                                        events.offer(
                                                                                BinaryMessages
                                                                                        .decodeEvent(
                                                                                                msg
                                                                                                        .getBody()));
                                                                    } finally {
                                                                        msg.release();
                                                                    }
                                                                } else {
                                                                    responses.offer(msg);
                                                                }
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
            if (response == null) {
                return "TIMEOUT";
            }
            try {
                if (response.getHeader().opcode() == Opcode.READY) {
                    return "SUCCESS";
                } else if (response.getHeader().opcode() == Opcode.ERROR) {
                    return BufferUtil.readString(response.getBody());
                }
                return "UNKNOWN";
            } finally {
                response.release();
            }

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
        try {
            return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
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

    /** Reads a server ACK confirming a SEND message was committed. */
    public UUID readAck() {
        Envelope env = readEnvelope(Duration.ofSeconds(2));
        if (env == null) {
            return null;
        }
        try {
            if (env.getHeader().opcode() == Opcode.ACK) {
                return BinaryMessages.decodeAckResponse(env.getBody());
            }
            return null;
        } finally {
            env.release();
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

    public void ackDeliveredWatermark(UUID conversationId, UUID watermarkEventId) {
        sendReceipt(
                conversationId,
                Opcode.ACK_DELIVERED,
                BinaryMessages.ReceiptRequest.watermark(conversationId, watermarkEventId));
    }

    public void ackReadWatermark(UUID conversationId, UUID watermarkEventId) {
        sendReceipt(
                conversationId,
                Opcode.ACK_READ,
                BinaryMessages.ReceiptRequest.watermark(conversationId, watermarkEventId));
    }

    public void ackReadExplicit(UUID conversationId, List<UUID> eventIds) {
        sendReceipt(
                conversationId,
                Opcode.ACK_READ,
                BinaryMessages.ReceiptRequest.explicit(conversationId, eventIds));
    }

    private void sendReceipt(
            UUID conversationId, Opcode opcode, BinaryMessages.ReceiptRequest request) {
        try {
            int streamId = streamSequence.incrementAndGet();
            ByteBuf body = channel.alloc().buffer();
            BinaryMessages.ReceiptRequest.encode(request, body);

            Envelope envelope = Envelope.create(opcode, streamId, body);
            channel.writeAndFlush(envelope).sync();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
