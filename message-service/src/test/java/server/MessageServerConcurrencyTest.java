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

import config.ServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jwt.JwtUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.handler.TestClient;

class MessageServerConcurrencyTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void setupKeys() throws Exception {
        keyPair = TestKeyManager.getKeyPair();
        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        JwtUtil.getInstance().init(publicKeyBase64);
    }

    @Test
    void concurrentSendTest() throws Exception {
        int clientCount = 20;
        ServerConfig config =
                org.aeonbits.owner.ConfigFactory.create(
                        ServerConfig.class, java.util.Map.of("PORT", "0"));
        MessageServer server = new MessageServer(config);
        server.start();
        int port = server.getPort();

        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);
        List<TestClient> clients = new ArrayList<>();

        try {
            for (int i = 0; i < clientCount; i++) {
                String userId = "user" + i;
                String token = createJwtToken(userId, keyPair.getPrivate());

                TestClient client = new TestClient(new java.net.Socket("127.0.0.1", port));
                client.send("INIT " + token + "\n");

                String response = client.readLine();
                Assertions.assertEquals("SUCCESS", response != null ? response.trim() : null);
                clients.add(client);
            }

            for (int i = 0; i < clientCount; i++) {
                int senderIdx = i;
                int recipientIdx = (i + 1) % clientCount;
                executor.submit(
                        () -> {
                            try {
                                clients.get(senderIdx)
                                        .send(
                                                "SEND user"
                                                        + recipientIdx
                                                        + " payload"
                                                        + senderIdx
                                                        + "\n");
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
                Assertions.assertEquals(
                        "FROM user" + senderIdx + " payload" + senderIdx,
                        recipient.readLine().trim());
            }

            Assertions.assertEquals(clientCount, server.getConnectionTracker().getSize());

        } finally {
            executor.shutdown();
            for (TestClient c : clients) c.close();
            server.stop();
        }
    }

    private static String createJwtToken(String userId, PrivateKey privateKey) throws Exception {
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        String payload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));

        String contentToSign = header + "." + payload;

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(contentToSign.getBytes(StandardCharsets.UTF_8));

        String sigBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return contentToSign + "." + sigBase64;
    }
}
