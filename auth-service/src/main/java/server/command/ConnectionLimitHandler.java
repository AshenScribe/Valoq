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
package server.command;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sharable handler placed at the head of the pipeline to limit concurrent connections globally and
 * per-IP.
 */
@ChannelHandler.Sharable
public final class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionLimitHandler.class);
    private static final AttributeKey<InetAddress> REMOTE_IP_KEY =
            AttributeKey.valueOf(ConnectionLimitHandler.class, "REMOTE_IP");

    private final long globalMaxConnections;
    private final long maxConnectionsPerIp;

    private final AtomicLong globalCounter = new AtomicLong(0);
    private final ConcurrentMap<InetAddress, AtomicLong> perIpCounters = new ConcurrentHashMap<>();

    public ConnectionLimitHandler(long globalMaxConnections, long maxConnectionsPerIp) {
        this.globalMaxConnections = globalMaxConnections;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
    }

    /** Default production settings: 5,000 global connections, 50 per IP. */
    public ConnectionLimitHandler() {
        this(5000, 50);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        long currentGlobal = globalCounter.incrementAndGet();

        if (globalMaxConnections > 0 && currentGlobal > globalMaxConnections) {
            LOGGER.warn(
                    "Exceeded maximum global connection limit of {} (current: {}). Closing socket from {}",
                    globalMaxConnections,
                    currentGlobal,
                    ctx.channel().remoteAddress());
            ctx.close();
            return;
        }

        if (maxConnectionsPerIp > 0) {
            InetAddress remoteIp = extractAndStoreRemoteIp(ctx.channel());
            if (remoteIp != null) {
                AtomicLong ipCounter =
                        perIpCounters.computeIfAbsent(remoteIp, k -> new AtomicLong(0));
                long currentForIp = ipCounter.incrementAndGet();

                if (currentForIp > maxConnectionsPerIp) {
                    LOGGER.warn(
                            "Exceeded maximum per-IP connection limit of {} from {} (current: {}). Closing socket.",
                            maxConnectionsPerIp,
                            remoteIp,
                            currentForIp);
                    ctx.close();
                    return;
                }
            }
        }

        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        globalCounter.decrementAndGet();

        InetAddress remoteIp = ctx.channel().attr(REMOTE_IP_KEY).get();
        if (remoteIp != null) {
            AtomicLong ipCounter = perIpCounters.get(remoteIp);
            if (ipCounter != null) {
                if (ipCounter.decrementAndGet() <= 0) {
                    perIpCounters.remove(remoteIp, ipCounter);
                }
            }
        }

        ctx.fireChannelInactive();
    }

    private static InetAddress extractAndStoreRemoteIp(Channel channel) {
        Attribute<InetAddress> attr = channel.attr(REMOTE_IP_KEY);
        SocketAddress remoteAddress = channel.remoteAddress();

        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            attr.setIfAbsent(address);
            return address;
        }
        return null;
    }

    public long getGlobalConnectionCount() {
        return globalCounter.get();
    }

    public long getConnectionCountForIp(InetAddress ip) {
        AtomicLong count = perIpCounters.get(ip);
        return count != null ? count.get() : 0;
    }
}
