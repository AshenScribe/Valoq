package database;

import database.entity.UserEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserRepository {

    private final Connection connection;
    private static final String AUTH_QUERY = "SELECT * FROM users WHERE username = ?";

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
}
