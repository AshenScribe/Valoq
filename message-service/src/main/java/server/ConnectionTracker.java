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

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionTracker {

    private final ChannelGroup allChannels;
    private final ConcurrentHashMap<UUID, Set<Channel>> userChannels = new ConcurrentHashMap<>();

    public ConnectionTracker() {
        this(new DefaultChannelGroup("all-client-connections", GlobalEventExecutor.INSTANCE));
    }

    public ConnectionTracker(ChannelGroup channelGroup) {
        this.allChannels = channelGroup;
    }

    public ClientConnection track(Channel channel) {
        ClientConnection connection = new ClientConnection(channel);
        connection.attachToChannel();
        allChannels.add(channel);
        return connection;
    }

    public void register(UUID userId, Channel channel) {
        allChannels.add(channel);
        userChannels.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(channel);

        ClientConnection conn = ClientConnection.get(channel);
        if (conn != null) {
            conn.authenticate(userId);
        } else {
            conn = new ClientConnection(channel);
            conn.attachToChannel();
            conn.authenticate(userId);
        }

        Session session = channel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).get();
        if (session != null) {
            session.setUserId(userId);
        }
    }

    public void register(String userId, Channel channel) {
        register(UUID.fromString(userId), channel);
    }

    public boolean unregister(UUID userId, Channel channel) {
        allChannels.remove(channel);
        if (userId == null) {
            return false;
        }

        Set<Channel> channels = userChannels.get(userId);
        if (channels != null) {
            boolean removed = channels.remove(channel);
            if (channels.isEmpty()) {
                userChannels.remove(userId);
            }
            return removed;
        }
        return false;
    }

    public boolean unregister(String userId, Channel channel) {
        if (userId == null) {
            return false;
        }
        return unregister(UUID.fromString(userId), channel);
    }

    /** Dispatches a message to a single user's devices with immediate flush. */
    public int sendToUser(UUID userId, String message) {
        Set<Channel> channels = userChannels.get(userId);
        if (channels == null || channels.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        for (Channel ch : channels) {
            if (ch.isActive()) {
                ch.writeAndFlush(message);
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * High-performance Cassandra-inspired fanout: Queues writes across all recipient channels and
     * issues flush calls efficiently, avoiding syscall thrashing on large recipient lists.
     */
    public int broadcastToUsers(Collection<UUID> userIds, String message) {
        List<Channel> channelsToFlush = new ArrayList<>();

        for (UUID userId : userIds) {
            Set<Channel> channels = userChannels.get(userId);
            if (channels != null) {
                for (Channel ch : channels) {
                    if (ch.isActive()) {
                        ch.write(message); // Write to buffer without immediate kernel syscall
                        channelsToFlush.add(ch);
                    }
                }
            }
        }

        // Flush each active channel once
        for (Channel ch : channelsToFlush) {
            ch.flush();
        }

        return channelsToFlush.size();
    }

    public Channel get(UUID userId) {
        Set<Channel> channels = userChannels.get(userId);
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        for (Channel ch : channels) {
            if (ch.isActive()) {
                return ch;
            }
        }
        return null;
    }

    public Channel get(String userId) {
        try {
            return get(UUID.fromString(userId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Set<Channel> getAllChannelsForUser(UUID userId) {
        Set<Channel> channels = userChannels.get(userId);
        return channels == null ? Collections.emptySet() : Collections.unmodifiableSet(channels);
    }

    public void closeAll() {
        allChannels.close().awaitUninterruptibly();
        userChannels.clear();
    }

    public boolean isUserOnline(UUID userId) {
        Set<Channel> channels = userChannels.get(userId);
        return channels != null && channels.stream().anyMatch(Channel::isActive);
    }

    public int getConnectedUserCount() {
        return userChannels.size();
    }

    public int getTotalChannelCount() {
        return allChannels.size();
    }

    public int getSize() {
        return userChannels.size();
    }

    /** Broadcasts a notice to all connected channels (e.g. during server shutdown). */
    public void broadcastNotice(String noticeMessage) {
        if (!allChannels.isEmpty()) {
            allChannels.writeAndFlush(noticeMessage);
        }
    }
}
