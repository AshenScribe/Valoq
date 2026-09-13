package server;

import io.netty.channel.Channel;

public class MessageRouter {

    private final ConnectionTracker connectionTracker;

    public MessageRouter(ConnectionTracker connectionTracker) {
        this.connectionTracker = connectionTracker;
    }

    public boolean route(String senderId, String recipientId, String payload) {
        Channel recipientChannel = connectionTracker.get(recipientId);

        if (recipientChannel == null) {
            return false;
        }

        recipientChannel.writeAndFlush(String.format("FROM %s %s\n", senderId, payload));
        return true;
    }
}
