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

import cache.RedisRepository;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import protocol.BinaryMessages;
import protocol.Envelope;

public class ConnectionTracker {

    private final ChannelGroup allChannels;
    private final ConcurrentHashMap<UUID, Set<Channel>> userChannels = new ConcurrentHashMap<>();
    private final RedisRepository redisRepository;
    private final String nodeId;

    public ConnectionTracker(String nodeId) {
        this(
                new DefaultChannelGroup("all-client-connections", GlobalEventExecutor.INSTANCE),
                new RedisRepository(),
                nodeId);
    }

    public ConnectionTracker(
            ChannelGroup channelGroup, RedisRepository redisRepository, String nodeId) {
        this.allChannels = channelGroup;
        this.redisRepository = redisRepository;
        this.nodeId = nodeId;
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
        redisRepository.write("user_presence:" + userId, nodeId);
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
                redisRepository.delete(userId.toString());
            }
            return removed;
        }
        return false;
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

    public void closeAll() {
        allChannels.close().awaitUninterruptibly();
        userChannels.clear();
    }

    public int getSize() {
        return userChannels.size();
    }

    /**
     * Broadcasts an Envelope across all recipient channels. Each channel receives a retained slice
     * so one channel's encoder releasing the buffer does not deallocate it for other channels.
     */
    public int broadcastToUsers(Collection<UUID> userIds, Envelope envelope) {
        List<Channel> channelsToFlush = new ArrayList<>();

        try {
            for (UUID userId : userIds) {
                Set<Channel> channels = userChannels.get(userId);
                if (channels != null) {
                    for (Channel ch : channels) {
                        if (ch.isActive()) {
                            // Retain buffer reference for this channel
                            ch.write(envelope.duplicate());
                            channelsToFlush.add(ch);
                        }
                    }
                }
            }

            for (Channel ch : channelsToFlush) {
                ch.flush();
            }
        } finally {
            // Release the original envelope
            envelope.release();
        }

        return channelsToFlush.size();
    }

    public int sendToUser(UUID userId, Envelope envelope) {
        Set<Channel> channels = userChannels.get(userId);
        if (channels != null && !channels.isEmpty()) {
            int delivered = 0;
            try {
                for (Channel ch : channels) {
                    if (ch.isActive()) {
                        ch.writeAndFlush(envelope.duplicate());
                        delivered++;
                    }
                }
            } finally {
                envelope.release();
            }
            return delivered;
        } else {
            redisRepository
                    .read("user_presence:" + userId)
                    .thenAccept(
                            targetNodeId -> {
                                try {
                                    if (targetNodeId != null) {
                                        redisRepository.publish(
                                                "node:" + targetNodeId, envelope.toString());
                                    }
                                } finally {
                                    envelope.release();
                                }
                            });
            return 0;
        }
    }

    public void broadcastNotice(String noticeMessage) {
        // Send error/notice envelope on shutdown
        if (!allChannels.isEmpty()) {
            for (Channel ch : allChannels) {
                if (ch.isActive()) {
                    Envelope notice =
                            BinaryMessages.createErrorResponse(
                                    Envelope.EVENT_STREAM_ID, noticeMessage, ch.alloc());
                    ch.write(notice);
                }
            }
            allChannels.flush();
        }
    }
}
