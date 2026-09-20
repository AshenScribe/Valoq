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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.cassandra.CassandraContainer;
import server.MessageServer;

public abstract class BaseIntegrationTest {

    protected static final CassandraContainer cassandra =
            new CassandraContainer("cassandra:5.0").withInitScript("init.cql");

    protected static ServerConfig serverConfig;
    protected static MessageServer server;
    protected static int serverPort;
    protected static CqlSession session;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        if (!cassandra.isRunning()) {
            cassandra.start();
        }

        ServerConfig.DatabaseConfig cassandraConfig = new ServerConfig.DatabaseConfig();
        cassandraConfig.setContactPoint(cassandra.getHost());
        cassandraConfig.setPort(cassandra.getFirstMappedPort());
        cassandraConfig.setLocalDatacenter(cassandra.getLocalDatacenter());
        cassandraConfig.setKeyspace("valoq_messages");
        CassandraManager.init(cassandraConfig);
        session = CassandraManager.getSession();

        serverConfig = new ServerConfig();

        ServerConfig.ServerSettings serverSettings = new ServerConfig.ServerSettings();
        serverSettings.setPort(0);
        serverConfig.setServer(serverSettings);

        ServerConfig.DatabaseConfig dbConfig = new ServerConfig.DatabaseConfig();
        dbConfig.setHost(cassandra.getHost());
        dbConfig.setPort(cassandra.getFirstMappedPort());
        serverConfig.setDatabase(dbConfig);

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
        return new MessageTestClient("127.0.0.1", serverPort);
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
}
