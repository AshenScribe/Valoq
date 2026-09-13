package server;

import io.netty.channel.Channel;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionTracker {

    private final ConcurrentHashMap<String, Channel> connections = new ConcurrentHashMap<>();

    public void register(String userId, Channel channel) {
        connections.put(userId, channel);
    }

    public boolean unregister(String userId, Channel channel) {
        return connections.remove(userId, channel);
    }

    public Channel get(String userId) {
        return connections.get(userId);
    }

    public int getSize() {
        return connections.size();
    }
}
