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

import authenticator.jwt.JwtUtil;
import client.AuthTestClient;
import config.ServerConfig;
import database.DatabaseManager;
import fixture.UserFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;
import server.AuthServer;

public abstract class BaseIntegrationTest {

    protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15.3")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init.sql");

    protected static ServerConfig config;
    protected static AuthServer server;
    protected static int serverPort;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        if (!postgres.isRunning()) {
            postgres.start();
        }

        config = new ServerConfig();
        config.setDatabaseHost(postgres.getHost());
        config.setDatabasePort(postgres.getFirstMappedPort());
        config.setDatabaseName(postgres.getDatabaseName());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setServerPort(0);
        config.setJwtExpirationTime(3600);

        DatabaseManager.init(config);
        JwtUtil.getInstance(config.jwtExpirationTime());

        server = new AuthServer(config);
        server.start();
        serverPort = server.getPort();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (server != null) server.stop();
        DatabaseManager.close();
    }

    @AfterEach
    void resetDatabase() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    """
                DO $$ DECLARE r RECORD;
                BEGIN
                    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public') LOOP
                        EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' RESTART IDENTITY CASCADE';
                    END LOOP;
                END $$;
            """);
        }
    }

    protected AuthTestClient createClient() throws InterruptedException {
        return new AuthTestClient("localhost", serverPort);
    }

    protected UserFixture users() throws SQLException {
        return new UserFixture();
    }
}
