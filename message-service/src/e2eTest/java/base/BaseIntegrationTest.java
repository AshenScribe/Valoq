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
package base;

import client.MessageTestClient;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import config.ServerConfig;
import database.CassandraManager;
import java.security.KeyPair;
import java.util.Map;
import org.aeonbits.owner.ConfigFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.cassandra.CassandraContainer;
import server.MessageServer;
import server.TestKeyManager;

public abstract class BaseIntegrationTest {

    protected static final CassandraContainer CASSANDRA_CONTAINER =
            new CassandraContainer("cassandra:5.0")
                    .withConfigurationOverride("cassandra-auth")
                    .withInitScript("init.cql")
                    .withCreateContainerCmdModifier(
                            cmd -> cmd.withName("cassandra-integration-test"));

    private static MessageServer server;
    private static CqlSession session;

    private static int serverPort;

    private static KeyPair keyPair;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = TestKeyManager.getKeyPair();
        String publicKeyBase64 =
                java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        jwt.JwtUtil.getInstance().init(publicKeyBase64);

        if (!CASSANDRA_CONTAINER.isRunning()) {
            CASSANDRA_CONTAINER.start();
        }

        Map<String, String> testProps =
                Map.of(
                        "DB_HOST",
                        CASSANDRA_CONTAINER.getHost(),
                        "DB_PORT",
                        String.valueOf(CASSANDRA_CONTAINER.getFirstMappedPort()),
                        "DB_LOCAL_DATACENTER",
                        CASSANDRA_CONTAINER.getLocalDatacenter(),
                        "DB_KEYSPACE",
                        "valoq_messages",
                        "DB_SSL_ENABLED",
                        "false",
                        "PORT",
                        "0");
        ServerConfig serverConfig = ConfigFactory.create(ServerConfig.class, testProps);

        CassandraManager.init(serverConfig);
        session = CassandraManager.getSession();

        server = new MessageServer(serverConfig);
        server.start();
        serverPort = server.getPort();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (server != null) {
            server.stop();
        }
        CassandraManager.close();
    }

    @AfterEach
    void cleanDatabase() {
        if (session != null && !session.isClosed()) {
            session.execute("TRUNCATE valoq_messages.messages;");
        }
    }

    protected MessageTestClient connect() throws InterruptedException {
        return new MessageTestClient("127.0.0.1", serverPort, keyPair.getPrivate());
    }

    protected Row awaitRow(String cqlQuery) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Row row = session.execute(cqlQuery).one();
            if (row != null) {
                return row;
            }
            Thread.sleep(50);
        }
        return null;
    }

    public static MessageServer getServer() {
        return server;
    }

    public static CqlSession getSession() {
        return session;
    }
}
