package server;

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
		MessageServer server = new MessageServer(0);
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
