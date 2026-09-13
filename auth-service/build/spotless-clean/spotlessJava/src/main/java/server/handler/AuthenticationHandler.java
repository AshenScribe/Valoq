package server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import server.command.AuthCommand;

public class AuthenticationHandler extends SimpleChannelInboundHandler<AuthCommand> {

    private static final ExecutorService AUTH_WORKERS = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, AuthCommand msg) {
        CompletableFuture.supplyAsync(
                        () -> AuthenticationHandlerFactory.getAuthenticationHandler(msg)
                                .login(msg),
                        AUTH_WORKERS)
                .thenAccept(token -> {
                    ctx.channel().eventLoop().execute(() -> {
                        ctx.writeAndFlush(token);
                    });
                })
                .exceptionally(ex -> {
                    ctx.channel().eventLoop().execute(() -> {
                        ctx.fireExceptionCaught(ex);
                    });
                    return null;
                });
    }
}
