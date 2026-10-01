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
import io.netty.util.AttributeKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class ClientConnection {

    public static final AttributeKey<ClientConnection> CONNECTION_KEY =
            AttributeKey.valueOf("CLIENT_CONNECTION");

    public enum State {
        CONNECTED,
        AUTHENTICATED,
        CLOSING
    }

    private final UUID connectionId;
    private final Channel channel;
    private final Instant connectedAt;
    private final AtomicLong messagesDelivered = new AtomicLong(0);

    private volatile UUID userId;
    private volatile State state;

    public ClientConnection(Channel channel) {
        this.connectionId = UUID.randomUUID();
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.connectedAt = Instant.now();
        this.state = State.CONNECTED;
    }

    public static ClientConnection get(Channel channel) {
        return channel.attr(CONNECTION_KEY).get();
    }

    public void attachToChannel() {
        channel.attr(CONNECTION_KEY).set(this);
    }

    public void authenticate(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.state = State.AUTHENTICATED;
    }

    public boolean isAuthenticated() {
        return state == State.AUTHENTICATED && userId != null;
    }

    public void markClosing() {
        this.state = State.CLOSING;
    }

    public void send(String message) {
        if (channel.isActive()) {
            channel.writeAndFlush(message.endsWith("\n") ? message : message + "\n");
            messagesDelivered.incrementAndGet();
        }
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public UUID getUserId() {
        return userId;
    }

    public State getState() {
        return state;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public long getMessagesDelivered() {
        return messagesDelivered.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClientConnection that = (ClientConnection) o;
        return connectionId.equals(that.connectionId);
    }

    @Override
    public int hashCode() {
        return connectionId.hashCode();
    }
}
