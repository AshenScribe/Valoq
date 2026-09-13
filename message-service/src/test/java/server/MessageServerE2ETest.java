package server;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.handler.TestClient;

class MessageServerE2ETest {

    private MessageServer server;
    private int port;

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new MessageServer(0);
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void endToEndMessagingFlow() throws Exception {
        try (TestClient alice = connect();
                TestClient bob = connect()) {

            // INIT
            alice.send("INIT alice\n");
            Assertions.assertEquals("SUCCESS\n", alice.readLine());

            bob.send("INIT bob\n");
            Assertions.assertEquals("SUCCESS\n", bob.readLine());

            // Alice -> Bob
            alice.send("SEND bob SGVsbG8=\n");

            Assertions.assertEquals("FROM alice SGVsbG8=\n", bob.readLine());

            // Bob -> Alice
            bob.send("SEND alice V29ybGQ=\n");

            Assertions.assertEquals("FROM bob V29ybGQ=\n", alice.readLine());
        }
    }

    @Test
    void multipleClientsCanCommunicateIndependently() throws Exception {
        try (TestClient alice = connect();
                TestClient bob = connect();
                TestClient charlie = connect()) {

            init(alice, "alice");
            init(bob, "bob");
            init(charlie, "charlie");

            alice.send("SEND bob SGVsbG8=\n");
            alice.send("SEND charlie Q2hhcmxpZQ==\n");

            Assertions.assertEquals("FROM alice SGVsbG8=\n", bob.readLine());

            Assertions.assertEquals("FROM alice Q2hhcmxpZQ==\n", charlie.readLine());

            Assertions.assertNull(bob.readLine(Duration.ofMillis(100)));
            Assertions.assertNull(charlie.readLine(Duration.ofMillis(100)));
        }
    }

    @Test
    void messagesArriveInOrder() throws Exception {
        try (TestClient alice = connect();
                TestClient bob = connect()) {

            init(alice, "alice");
            init(bob, "bob");

            alice.send("SEND bob SGVsbG8=\n");
            alice.send("SEND bob V29ybGQ=\n");
            alice.send("SEND bob VGhpcmQ=\n");

            Assertions.assertEquals("FROM alice SGVsbG8=\n", bob.readLine());

            Assertions.assertEquals("FROM alice V29ybGQ=\n", bob.readLine());

            Assertions.assertEquals("FROM alice VGhpcmQ=\n", bob.readLine());
        }
    }

    @Test
    void unknownRecipientReturnsError() throws Exception {
        try (TestClient alice = connect()) {

            init(alice, "alice");

            alice.send("SEND nobody SGVsbG8=\n");

            Assertions.assertEquals("ERROR Recipient not connected\n", alice.readLine());
        }
    }

    @Test
    void malformedSendReturnsError() throws Exception {
        try (TestClient alice = connect()) {

            init(alice, "alice");

            alice.send("SEND\n");

            Assertions.assertEquals("ERROR Invalid SEND format\n", alice.readLine());
        }
    }

    @Test
    void sendBeforeInitReturnsError() throws Exception {
        try (TestClient alice = connect()) {

            alice.send("SEND bob SGVsbG8=\n");

            Assertions.assertEquals("INVALID\n", alice.readLine());

            Assertions.assertTrue(alice.serverClosedConnection());
        }
    }

    @Test
    void invalidInitReturnsInvalidAndClosesConnection() throws Exception {
        try (TestClient client = connect()) {

            client.send("init alice\n");

            Assertions.assertEquals("INVALID\n", client.readLine());

            Assertions.assertTrue(client.serverClosedConnection());
        }
    }

    @Test
    void validInitRegistersConnection() throws Exception {
        try (TestClient alice = connect()) {

            init(alice, "alice");

            alice.send("SEND alice SGVsbG8=\n");

            Assertions.assertEquals("FROM alice SGVsbG8=\n", alice.readLine());
        }
    }

    @Test
    void duplicateUserUsesLatestConnection() throws Exception {
        try (TestClient firstAlice = connect();
                TestClient secondAlice = connect();
                TestClient bob = connect()) {

            init(firstAlice, "alice");
            init(bob, "bob");

            init(secondAlice, "alice");

            bob.send("SEND alice SGVsbG8=\n");

            Assertions.assertEquals("FROM bob SGVsbG8=\n", secondAlice.readLine());

            Assertions.assertNull(firstAlice.readLine(Duration.ofMillis(100)));
        }
    }

    @Test
    void disconnectedRecipientIsNoLongerReachable() throws Exception {
        try (TestClient alice = connect();
                TestClient bob = connect()) {

            init(alice, "alice");
            init(bob, "bob");

            bob.close();

            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (server.getConnectionTracker().get("bob") != null) {
                    Thread.sleep(10);
                }
            });

            alice.send("SEND bob SGVsbG8=\n");

            Assertions.assertEquals("ERROR Recipient not connected\n", alice.readLine());
        }
    }

    @Test
    void manyClientsCanBeRegisteredAndRouted() throws Exception {
        int clientCount = 20;

        List<TestClient> clients = new ArrayList<>();

        try {
            for (int i = 0; i < clientCount; i++) {
                TestClient client = connect();
                clients.add(client);

                init(client, "user" + i);
            }

            for (int i = 0; i < clientCount; i++) {
                TestClient sender = clients.get(i);

                int recipientIndex = (i + 1) % clientCount;

                sender.send("SEND user" + recipientIndex + " SGVsbG8=\n");
            }

            for (int i = 0; i < clientCount; i++) {
                int senderIndex = (i - 1 + clientCount) % clientCount;

                TestClient recipient = clients.get(i);

                Assertions.assertEquals("FROM user" + senderIndex + " SGVsbG8=\n", recipient.readLine());
            }
        } finally {
            for (TestClient client : clients) {
                client.close();
            }
        }
    }

    @Test
    void fragmentedTcpWriteIsReassembledIntoSingleFrame() throws Exception {
        try (TestClient alice = connect()) {
            alice.send("INIT al");
            alice.send("ice\n");

            Assertions.assertEquals("SUCCESS\n", alice.readLine());
        }
    }

    @Test
    void multipleFramesInSingleTcpWriteAreProcessedIndependently() throws Exception {
        try (TestClient alice = connect();
                TestClient bob = connect()) {
            init(bob, "bob");

            alice.send("INIT alice\nSEND bob SGVsbG8=\n");

            Assertions.assertEquals("SUCCESS\n", alice.readLine());
            Assertions.assertEquals("FROM alice SGVsbG8=\n", bob.readLine());
        }
    }

    private TestClient connect() throws IOException {
        return new TestClient(new Socket("127.0.0.1", port));
    }

    private void init(TestClient client, String userId) throws IOException {

        client.send("INIT " + userId + "\n");

        Assertions.assertEquals("SUCCESS\n", client.readLine());
    }
}
