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
}
