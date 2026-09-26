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
        User user =
                new User(
                        "usr_" + UUID.randomUUID().toString().substring(0, 8),
                        username,
                        passwordHash,
                        salt,
                        username + "@test.com");

        String sql =
                "INSERT INTO users (user_id, username, password_hash, salt, email) VALUES (?, ?, ?, ?, ?)";
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
