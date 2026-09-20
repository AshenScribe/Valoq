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

import config.ConfigLoader;
import config.ServerConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import server.handler.TestClient;

class MessageServerConcurrencyTest {

    @Test
    void concurrentSendTest() throws Exception {
        int clientCount = 20;
        ServerConfig config = new ConfigLoader().appConfig();
        MessageServer server = new MessageServer(config);
        server.start();
        int port = server.getPort();

        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);
        List<TestClient> clients = new ArrayList<>();

        try {
            for (int i = 0; i < clientCount; i++) {
                TestClient client = new TestClient(new java.net.Socket("127.0.0.1", port));
                client.send("INIT user" + i + "\n");
                client.readLine();
                clients.add(client);
            }

            for (int i = 0; i < clientCount; i++) {
                int senderIdx = i;
                int recipientIdx = (i + 1) % clientCount;
                executor.submit(() -> {
                    try {
                        clients.get(senderIdx).send("SEND user" + recipientIdx + " payload" + senderIdx + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < clientCount; i++) {
                int senderIdx = (i - 1 + clientCount) % clientCount;
                TestClient recipient = clients.get(i);
                Assertions.assertEquals("FROM user" + senderIdx + " payload" + senderIdx + "\n", recipient.readLine());
            }

            Assertions.assertEquals(clientCount, server.getConnectionTracker().getSize());

        } finally {
            executor.shutdown();
            for (TestClient c : clients) c.close();
            server.stop();
        }
    }
}
