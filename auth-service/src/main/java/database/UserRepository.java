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

import database.entity.UserEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class UserRepository {

    private final Connection connection;
    private static final String AUTH_QUERY = "SELECT * FROM users WHERE username = ?";
    private static final String INSERT_USER_QUERY =
            "INSERT INTO users (user_id, username, password_hash, salt, email) VALUES (?, ?, ?, ?, ?)";

    public UserRepository() {
        this.connection = null;
    }

    public UserRepository(Connection connection) {
        this.connection = connection;
    }

    public UserEntity getUser(String username) {
        boolean shouldClose = (this.connection == null);
        Connection conn = null;
        try {
            conn = shouldClose ? DatabaseManager.getConnection() : this.connection;
            try (PreparedStatement statement = conn.prepareStatement(AUTH_QUERY)) {
                statement.setString(1, username);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        final String id = resultSet.getString("user_id");
                        final String passwordHash = resultSet.getString("password_hash");
                        final String dbUsername = resultSet.getString("username");
                        final String salt = resultSet.getString("salt");
                        final String email = resultSet.getString("email");
                        return new UserEntity(id, dbUsername, passwordHash, salt, email);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public String saveUser(String username, String password, String salt) {
        return saveUser(username, password, salt, null);
    }

    public String saveUser(String username, String password, String salt, String email) {
        String userId = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        boolean shouldClose = (this.connection == null);
        Connection conn = null;
        try {
            conn = shouldClose ? DatabaseManager.getConnection() : this.connection;
            try (PreparedStatement statement = conn.prepareStatement(INSERT_USER_QUERY)) {
                statement.setString(1, userId);
                statement.setString(2, username);
                statement.setString(3, password);
                statement.setString(4, salt);
                statement.setString(5, email);
                statement.executeUpdate();
            }
            return userId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save user: " + username, e);
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }
}
