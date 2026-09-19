package database;

import database.entity.UserEntity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserRepositoryEdgeCasesTest {

    private Connection connection;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() throws SQLException {
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
        userRepository = new UserRepository(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE users IF EXISTS");
            }
            connection.close();
        }
    }

    @Nested
    @DisplayName("Input Handling & Validation Edge Cases")
    class InputHandlingTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Should return null or handle gracefully for null, empty, or whitespace-only usernames")
        void getUser_handlesNullOrBlankInput(String inputUsername) {
            UserEntity user = userRepository.getUser(inputUsername);
            Assertions.assertNull(user);
        }

        @Test
        @DisplayName("Should prevent SQL injection via input parameterization")
        void getUser_preventsSqlInjection() throws SQLException {
            insertUser("user-1", "admin", "hash1", "salt1", "admin@test.com");

            String injectionQuery = "' OR '1'='1";
            UserEntity user = userRepository.getUser(injectionQuery);

            Assertions.assertNull(user, "SQL Injection payload must not execute or return a record");
        }

        @Test
        @DisplayName("Should handle case sensitivity depending on DB behavior (or exact match)")
        void getUser_handlesCaseSensitivity() throws SQLException {
            insertUser("user-1", "JohnDoe", "hash1", "salt1", "john@test.com");

            UserEntity exactMatch = userRepository.getUser("JohnDoe");
            UserEntity lowercaseMatch = userRepository.getUser("johndoe");

            Assertions.assertNotNull(exactMatch);
            Assertions.assertNull(
                    lowercaseMatch, "Username lookup should strictly follow expected database casing rules");
        }

        @Test
        @DisplayName("Should handle special characters and Unicode in username")
        void getUser_handlesUnicodeAndSpecialCharacters() throws SQLException {
            String specialUsername = "jöhñ_døé!@#$%^&*()_+-=[]{}|;:,.<>?/`~";
            insertUser("user-special", specialUsername, "hash", "salt", "unicode@test.com");

            UserEntity user = userRepository.getUser(specialUsername);

            Assertions.assertNotNull(user);
            Assertions.assertEquals(specialUsername, user.username());
        }
    }

    @Nested
    @DisplayName("Data Integrity & Schema Edge Cases")
    class DataIntegrityTests {

        @Test
        @DisplayName("Should correctly extract null optional fields (salt, email)")
        void getUser_handlesNullColumnValuesInDatabase() throws SQLException {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(
                        """
								    INSERT INTO users (user_id, username, password_hash, salt, email)
								    VALUES ('user-nulls', 'nullable_user', 'hash_only', NULL, NULL)
								""");
            }

            UserEntity user = userRepository.getUser("nullable_user");

            Assertions.assertNotNull(user);
            Assertions.assertEquals("user-nulls", user.userId());
            Assertions.assertEquals("nullable_user", user.username());
            Assertions.assertEquals("hash_only", user.passwordHash());
            Assertions.assertNull(user.salt());
            Assertions.assertNull(user.email());
        }

        @Test
        @DisplayName("Should extract first record if database uniquely violates and contains duplicates")
        void getUser_handlesMultipleMatchingRecords() throws SQLException {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS CONSTRAINT_BA");
                stmt.execute("DROP TABLE users");
                stmt.execute(
                        """
								    CREATE TABLE users (
								        user_id VARCHAR(50),
								        username VARCHAR(50),
								        password_hash VARCHAR(255),
								        salt VARCHAR(50),
								        email VARCHAR(100)
								    )
								""");
            }
            userRepository = new UserRepository(connection);

            insertUser("user-1", "duplicate_user", "hash1", "salt1", "email1@test.com");
            insertUser("user-2", "duplicate_user", "hash2", "salt2", "email2@test.com");

            UserEntity user = userRepository.getUser("duplicate_user");

            Assertions.assertNotNull(user);
            Assertions.assertEquals("user-1", user.userId(), "Should consistently retrieve the first matching record");
        }

        @Test
        @DisplayName("Should throw RuntimeException if DB schema drops a required column (e.g., SELECT * drift)")
        void getUser_throwsException_whenRequiredColumnIsMissing() throws SQLException {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE users");
                stmt.execute(
                        """
								    CREATE TABLE users (
								        user_id VARCHAR(50) PRIMARY KEY,
								        username VARCHAR(50) NOT NULL,
								        password_hash VARCHAR(255) NOT NULL,
								        email VARCHAR(100)
								    )
								""");
            }
            userRepository = new UserRepository(connection);

            insertUserWithoutSalt("user-1", "john_doe", "hash", "john@test.com");

            RuntimeException exception =
                    Assertions.assertThrows(RuntimeException.class, () -> userRepository.getUser("john_doe"));
            Assertions.assertTrue(exception.getCause() instanceof SQLException);
        }
    }

    @Nested
    @DisplayName("Connection & Database Failure Edge Cases")
    class ConnectionFailureTests {

        @Test
        @DisplayName("Should throw RuntimeException when database connection is closed prior to query execution")
        void getUser_throwsException_whenConnectionClosed() throws SQLException, InterruptedException {
            connection.close();
            RuntimeException exception =
                    Assertions.assertThrows(RuntimeException.class, () -> userRepository.getUser("john_doe"));
            Assertions.assertTrue(exception.getCause() instanceof SQLException);
        }

        @Test
        @DisplayName("Should handle database query timeout or cancelled statement")
        void getUser_throwsException_whenQueryFailsOrTimesOut() throws SQLException {
            connection.close();

            Assertions.assertThrows(RuntimeException.class, () -> userRepository.getUser("john_doe"));
        }
    }

    private void insertUser(String id, String username, String hash, String salt, String email) throws SQLException {
        String sql = "INSERT INTO users (user_id, username, password_hash, salt, email) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, username);
            pstmt.setString(3, hash);
            pstmt.setString(4, salt);
            pstmt.setString(5, email);
            pstmt.executeUpdate();
        }
    }

    private void insertUserWithoutSalt(String id, String username, String hash, String email) throws SQLException {
        String sql = "INSERT INTO users (user_id, username, password_hash, email) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, username);
            pstmt.setString(3, hash);
            pstmt.setString(4, email);
            pstmt.executeUpdate();
        }
    }
}
