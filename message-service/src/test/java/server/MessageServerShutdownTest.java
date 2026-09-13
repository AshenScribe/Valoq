package server;

import java.io.IOException;
import java.net.Socket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MessageServerShutdownTest {

    @Test
    void testGracefulShutdown() throws InterruptedException, IOException {
        MessageServer server = new MessageServer(0);
        server.start();
        int port = server.getPort();

        try (Socket s = new Socket("127.0.0.1", port)) {
            Assertions.assertTrue(s.isConnected());
        }

        server.stop();

        Assertions.assertThrows(IOException.class, () -> {
            new Socket("127.0.0.1", port);
        });
    }

    @Test
    void testRegistryCleanupOnShutdown() throws InterruptedException, IOException {
        MessageServer server = new MessageServer(0);
        server.start();
        
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.getPort())) {
            Assertions.assertEquals(0, server.getConnectionTracker().getSize());
        }
        
        server.stop();
        Assertions.assertEquals(0, server.getConnectionTracker().getSize());
    }
}