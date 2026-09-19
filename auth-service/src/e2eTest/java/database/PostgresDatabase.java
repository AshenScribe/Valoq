package database;

import config.ServerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import server.model.User;

public class PostgresDatabase {

    @Container
    public static PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.3")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init.sql");

    public static ServerConfig getServerConfig() {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setDatabaseHost(container.getHost());
        serverConfig.setDatabasePort(container.getFirstMappedPort());
        serverConfig.setDatabaseName(container.getDatabaseName());
        serverConfig.setUsername(container.getUsername());
        serverConfig.setPassword(container.getPassword());
        return serverConfig;
    }

    Connection getConnection() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    public void resetDatabase() {
        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    """
                DO $$ DECLARE
                    r RECORD;
                BEGIN
                    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public') LOOP
                        EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' RESTART IDENTITY CASCADE';
                    END LOOP;
                END $$;
            """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reset database state after test execution", e);
        }
    }

    public void insertTestUser(User u) {
        String sql =
                """
            INSERT INTO users (user_id, username, password_hash, salt, email)
            VALUES (?, ?, ?, ?, ?)
        """;

        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, u.userId());
            pstmt.setString(2, u.username());
            pstmt.setString(3, u.passwordHash());
            pstmt.setString(4, u.salt());
            pstmt.setString(5, u.email());

            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed test user: " + u.username(), e);
        }
    }
}
