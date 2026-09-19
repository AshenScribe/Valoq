package fixture;

import database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import server.model.User;

public class UserFixture {

    public User createUser() {
        return createUser("user_" + UUID.randomUUID().toString().substring(0, 8));
    }

    public User createUser(String username) {
        return createUser(username, "hash_" + username, "salt_" + username);
    }

    public User createUser(String username, String passwordHash, String salt) {
        User user = new User(
                "usr_" + UUID.randomUUID().toString().substring(0, 8),
                username,
                passwordHash,
                salt,
                username + "@test.com");

        String sql = "INSERT INTO users (user_id, username, password_hash, salt, email) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection(); // Auto-closed back to pool
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.userId());
            ps.setString(2, user.username());
            ps.setString(3, user.passwordHash());
            ps.setString(4, user.salt());
            ps.setString(5, user.email());
            ps.executeUpdate();
            return user;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert fixture user", e);
        }
    }
}
