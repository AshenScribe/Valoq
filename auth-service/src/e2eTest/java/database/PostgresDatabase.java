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
    public static PostgreSQLContainer container =
            new PostgreSQLContainer("postgres:15.3")
                    .withDatabaseName("testdb")
                    .withUsername("testuser")
                    .withPassword("testpass")
                    .withInitScript("init.sql");

    public static ServerConfig getServerConfig() {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.database().host();
        serverConfig.database().setPort(container.getFirstMappedPort());
        serverConfig.database().setName(container.getDatabaseName());
        serverConfig.database().setUsername(container.getUsername());
        serverConfig.database().setPassword(container.getPassword());
        return serverConfig;
    }

    Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
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
