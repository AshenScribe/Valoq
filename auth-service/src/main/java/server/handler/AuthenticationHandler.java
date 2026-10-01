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
package server.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import server.command.AuthCommand;

@ChannelHandler.Sharable
public class AuthenticationHandler extends SimpleChannelInboundHandler<AuthCommand> {

    private static final ExecutorService AUTH_WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore DB_CONCURRENCY_GATE = new Semaphore(20);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, AuthCommand msg) {
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                DB_CONCURRENCY_GATE.acquire();
                                try {
                                    return AuthenticationHandlerFactory.getAuthenticationHandler(
                                                    msg)
                                            .login(msg);
                                } finally {
                                    DB_CONCURRENCY_GATE.release();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Authentication interrupted", e);
                            }
                        },
                        AUTH_WORKERS)
                .whenComplete(
                        (token, ex) -> {
                            ctx.channel()
                                    .eventLoop()
                                    .execute(
                                            () -> {
                                                if (ex != null) {
                                                    ctx.fireExceptionCaught(ex);
                                                } else {
                                                    ctx.writeAndFlush(token);
                                                }
                                            });
                        });
    }
}
