package authenticator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import authenticator.jwt.JwtUtil;
import database.UserRepository;
import exception.UserNotFoundException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import server.command.AuthCommand;
import server.command.BasicCommand;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasicAuthenticatorTest {

    private Connection connection;
    private BasicAuthenticator basicAuthenticator;

    @BeforeAll
    void setupDatabase() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");

        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                CREATE TABLE IF NOT EXISTS users (
                    user_id VARCHAR(50) PRIMARY KEY,
                    username VARCHAR(50) NOT NULL UNIQUE,
                    password_hash VARCHAR(255) NOT NULL,
                    salt VARCHAR(50),
                    email VARCHAR(100)
                )
            """);
        }

        JwtUtil.getInstance(5000);

        UserRepository userRepository = new UserRepository(connection);
        basicAuthenticator = new BasicAuthenticator(userRepository);
    }

    @BeforeEach
    void clearTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM users");
        }
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private void insertUser(String userId, String username, String passwordHash, String salt, String email)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                """
            INSERT INTO users (user_id, username, password_hash, salt, email)
            VALUES (?, ?, ?, ?, ?)
        """)) {
            stmt.setString(1, userId);
            stmt.setString(2, username);
            stmt.setString(3, passwordHash);
            stmt.setString(4, salt);
            stmt.setString(5, email);
            stmt.executeUpdate();
        }
    }

    @Test
    @DisplayName("login: Should return valid JWT when credentials match")
    void login_Success() throws SQLException {
        insertUser("usr-101", "alice", "hashed_secret123", "salt1", "alice@example.com");

        AuthCommand command = new BasicCommand("alice", "hashed_secret123", "salt1");
        String jwtToken = basicAuthenticator.login(command);

        assertNotNull(jwtToken, "Token should not be null on successful login");
        assertFalse(jwtToken.isBlank(), "Token should not be blank");
    }

    @Test
    @DisplayName("login: Should allow login with special characters in credentials")
    void login_SpecialCharactersInUsernameAndPassword() throws SQLException {
        String salt = "1234567812345678";
        String password = "usr_admin!@#$%^&*()";
        String complexUser = "test_user!@#$%^&*()";

        insertUser("usr-102", complexUser, password, salt, "admin@domain.org");

        AuthCommand command = new BasicCommand(complexUser, password, salt);
        String token = basicAuthenticator.login(command);

        assertNotNull(token);
    }

    @Test
    @DisplayName("login: Should return null when password does not match")
    void login_WrongPassword_ReturnsNull() throws SQLException {
        insertUser("usr-103", "bob", "correct_hash", "salt3", "bob@example.com");

        AuthCommand command = new BasicCommand("bob", "wrong_hash", "salt");
        Assertions.assertThrows(UserNotFoundException.class, () -> basicAuthenticator.login(command));
    }

    @Test
    @DisplayName("login: Password matching should be strictly case-sensitive")
    void login_CaseSensitivePassword_ReturnsNull() throws SQLException {
        insertUser("usr-104", "charlie", "SecretHash123", "salt4", "charlie@example.com");

        AuthCommand command = new BasicCommand("charlie", "secrethash123", "salt");
        Assertions.assertThrows(UserNotFoundException.class, () -> basicAuthenticator.login(command));
    }

    @Test
    @DisplayName("login: Should handle empty strings for credentials")
    void login_EmptyCredentials() throws SQLException {
        insertUser("usr-107", "", "", "salt7", "empty@example.com");

        AuthCommand commandSuccess = new BasicCommand("", "", "");
        Assertions.assertNotNull(basicAuthenticator.login(commandSuccess));

        AuthCommand commandFail = new BasicCommand("", "non_empty", "");
        Assertions.assertThrows(UserNotFoundException.class, () -> basicAuthenticator.login(commandFail));
    }

    @Test
    @DisplayName("login: Should throw ClassCastException if AuthCommand is not BasicCommand")
    void login_InvalidCommandType_ThrowsClassCastException() {
        AuthCommand invalidCommand = new AuthCommand() {};

        assertThrows(ClassCastException.class, () -> {
            basicAuthenticator.login(invalidCommand);
        });
    }
}
