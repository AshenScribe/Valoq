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

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.command.AuthCommand;

@ChannelHandler.Sharable
public class AuthenticationHandler extends SimpleChannelInboundHandler<AuthCommand> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationHandler.class);
    public static final AuthenticationHandler INSTANCE = new AuthenticationHandler();
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("valoq.auth-service");

    // Dedicated worker executor for CPU/DB auth tasks (lazily initialized /
    // recreated on demand)
    private static volatile ExecutorService authWorkers =
            Executors.newVirtualThreadPerTaskExecutor();

    // Sized to match database pool capacity (HikariCP max 10)
    private static final Semaphore DB_CONCURRENCY_GATE = new Semaphore(10);

    public AuthenticationHandler() {}

    private static ExecutorService getWorkers() {
        ExecutorService workers = authWorkers;
        if (workers == null || workers.isShutdown()) {
            synchronized (AuthenticationHandler.class) {
                workers = authWorkers;
                if (workers == null || workers.isShutdown()) {
                    authWorkers = Executors.newVirtualThreadPerTaskExecutor();
                    workers = authWorkers;
                }
            }
        }
        return workers;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, AuthCommand msg) {
        ChannelConfig config = ctx.channel().config();
        if (config.isAutoRead()) {
            config.setAutoRead(false);
        }

        Context parentContext = Context.current();
        Span span =
                TRACER.spanBuilder("auth.authenticate")
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan();

        Context authContext = parentContext.with(span);

        try {
            CompletableFuture.supplyAsync(
                            () -> {
                                try (var scope = TracingMetadataHandler.makeCurrent(authContext)) {
                                    boolean acquired = false;

                                    try {
                                        acquired =
                                                DB_CONCURRENCY_GATE.tryAcquire(2, TimeUnit.SECONDS);

                                        if (!acquired) {
                                            throw new IllegalStateException(
                                                    "Server overloaded, please retry later");
                                        }

                                        if (!ctx.channel().isActive()) {
                                            LOGGER.debug(
                                                    "Client disconnected before auth execution started; "
                                                            + "discarding task");
                                            return null;
                                        }

                                        return AuthenticationHandlerFactory
                                                .getAuthenticationHandler(msg)
                                                .login(msg);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(
                                                "Authentication task interrupted", e);
                                    } finally {
                                        if (acquired) {
                                            DB_CONCURRENCY_GATE.release();
                                        }
                                    }
                                }
                            },
                            Context.taskWrapping(getWorkers()))
                    .whenComplete(
                            authContext.wrapConsumer(
                                    (token, ex) -> {
                                        try (var scope =
                                                TracingMetadataHandler.makeCurrent(authContext)) {
                                            if (ex != null) {
                                                Throwable error = ex;
                                                while ((error instanceof CompletionException
                                                                || error
                                                                        instanceof
                                                                        ExecutionException)
                                                        && error.getCause() != null) {
                                                    error = error.getCause();
                                                }

                                                span.recordException(error);
                                                span.setStatus(StatusCode.ERROR);
                                            }
                                        } finally {
                                            span.end();
                                        }
                                    }));
        } catch (Throwable t) {
            try {
                span.recordException(t);
                span.setStatus(StatusCode.ERROR);
            } finally {
                span.end();
            }

            if (ctx.channel().isOpen() && !config.isAutoRead()) {
                config.setAutoRead(true);
            }

            ctx.fireExceptionCaught(t);
        }
    }

    public static synchronized void shutdownWorkers() {
        if (authWorkers != null && !authWorkers.isShutdown()) {
            authWorkers.shutdown();
            try {
                if (!authWorkers.awaitTermination(5, TimeUnit.SECONDS)) {
                    authWorkers.shutdownNow();
                }
            } catch (InterruptedException e) {
                authWorkers.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                authWorkers = null;
            }
        }
    }
}
