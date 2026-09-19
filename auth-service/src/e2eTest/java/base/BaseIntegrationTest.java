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
