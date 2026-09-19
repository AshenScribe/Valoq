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
package authenticator;

import authenticator.jwt.JwtUtil;
import database.UserRepository;
import database.entity.UserEntity;
import exception.ValidationException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.command.AuthCommand;
import server.command.BasicCommand;
import server.command.RegisterCommand;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisterAuthenticatorTest {

    private Connection connection;
    private UserRepository userRepository;
    private RegisterAuthenticator registerAuthenticator;

    @BeforeAll
    void setupH2Database() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:register_testdb;DB_CLOSE_DELAY=-1", "sa", "");

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

        JwtUtil.getInstance(3600);
    }

    @BeforeEach
    void setUp() {
        userRepository = new UserRepository(connection);
        registerAuthenticator = new RegisterAuthenticator(userRepository);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM users");
            }
        }
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Nested
    @DisplayName("Boundary Value Limits")
    class BoundaryValueTests {

        @Test
        @DisplayName("Username: Lower boundary - exactly 3 characters should SUCCEED")
        void username_ExactMinBoundary_3Chars_Success() {
            String token =
                    registerAuthenticator.login(new RegisterCommand("abc", "ValidPass123!", "salt", "abc@test.com"));
            Assertions.assertNotNull(token);
        }

        @Test
        @DisplayName("Username: Upper boundary - exactly 32 characters should SUCCEED")
        void username_ExactMaxBoundary_32Chars_Success() {
            String username32 = "a".repeat(32);
            String token = registerAuthenticator.login(
                    new RegisterCommand(username32, "ValidPass123!", "salt", "max@test.com"));
            Assertions.assertNotNull(token);
        }

        @Test
        @DisplayName("Password: Lower boundary - exactly 8 characters should SUCCEED")
        void password_ExactMinBoundary_8Chars_Success() {
            String token =
                    registerAuthenticator.login(new RegisterCommand("valid_user", "Aa1!aaaa", "salt", "p8@test.com"));
            Assertions.assertNotNull(token);
        }

        @Test
        @DisplayName("Password: Upper boundary - exactly 64 characters should SUCCEED")
        void password_ExactMaxBoundary_64Chars_Success() {
            String password64 = "Aa1!" + "b".repeat(60);
            String token =
                    registerAuthenticator.login(new RegisterCommand("valid_user", password64, "salt", "p64@test.com"));
            Assertions.assertNotNull(token);
        }
    }

    @Nested
    @DisplayName("Null, Empty, and Whitespace Handling")
    class NullAndEmptyHandlingTests {

        @Test
        @DisplayName("Should throw NullPointerException when username is null")
        void register_NullUsername_ThrowsNpe() {
            Assertions.assertThrows(
                    NullPointerException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand(null, "ValidPass1!", "salt", "test@test.com")));
        }

        @Test
        @DisplayName("Should throw NullPointerException when password is null")
        void register_NullPassword_ThrowsNpe() {
            Assertions.assertThrows(
                    NullPointerException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand("valid_user", null, "salt", "test@test.com")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"   ", "\t\t\t", "\n\n\n", " a "})
        @DisplayName("Whitespace-only or space-padded usernames should be rejected")
        void register_WhitespaceUsernames_Rejected(String whitespaceUser) {
            Assertions.assertThrows(
                    ValidationException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand(whitespaceUser, "ValidPass1!", "salt", "ws@test.com")));
        }

        @Test
        @DisplayName("Optional fields: Registering with null salt and null email should SUCCEED")
        void register_NullSaltAndEmail_ShouldPersistSuccessfully() {
            String token =
                    registerAuthenticator.login(new RegisterCommand("user_no_optional", "ValidPass1!", null, null));
            Assertions.assertNotNull(token);

            UserEntity savedUser = userRepository.getUser("user_no_optional");
            Assertions.assertNotNull(savedUser);
            Assertions.assertNull(savedUser.salt(), "Salt column should store null without errors");
            Assertions.assertNull(savedUser.email(), "Email column should store null without errors");
        }
    }

    @Nested
    @DisplayName("Adversarial Inputs & Injection Attempts")
    class AdversarialPayloadTests {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "' OR '1'='1",
                    "admin'; DROP TABLE users; --",
                    "<script>alert(1)</script>",
                    "user;SELECT *",
                    "user$name",
                    "user\u0000nullbyte"
                })
        @DisplayName("Adversarial & SQLi username inputs must be blocked before reaching SQL")
        void register_AdversarialUsernames_BlockedByValidator(String maliciousUsername) {
            ValidationException ex = Assertions.assertThrows(
                    ValidationException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand(maliciousUsername, "ValidPass1!", "salt", "a@b.com")));
            Assertions.assertEquals("Username contains invalid characters", ex.getMessage());
        }

        @Test
        @DisplayName("SQL injection payload in password should be safely escaped by PreparedStatement")
        void register_SqlInjectionInPassword_SafelyEscaped() {
            String sqlPassword = "Admin';--123!\"#";

            String token = registerAuthenticator.login(
                    new RegisterCommand("sql_pass_user", sqlPassword, "salt", "test@test.com"));
            Assertions.assertNotNull(token);
            UserEntity user = userRepository.getUser("sql_pass_user");
            Assertions.assertNotNull(user);
            Assertions.assertEquals(sqlPassword, user.passwordHash());
        }

        @ParameterizedTest
        @ValueSource(strings = {"jöhñ_døé", "user_日本語", "user_🔥", "user\u200Bname"})
        @DisplayName("Non-ASCII and Unicode characters in username should be rejected")
        void register_UnicodeAndEmojisInUsername_Rejected(String unicodeUser) {
            Assertions.assertThrows(
                    ValidationException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand(unicodeUser, "ValidPass1!", "salt", "u@test.com")));
        }
    }

    @Nested
    @DisplayName("Command Contract Safety")
    class CommandContractTests {

        @Test
        @DisplayName("Passing wrong command type (e.g. BasicCommand) should throw ClassCastException")
        void register_WrongCommandType_ThrowsClassCastException() {
            AuthCommand wrongCommand = new BasicCommand("user", "pass", "salt");

            Assertions.assertThrows(ClassCastException.class, () -> registerAuthenticator.login(wrongCommand));
        }

        @Test
        @DisplayName("Passing null AuthCommand should throw NullPointerException")
        void register_NullCommand_ThrowsNullPointerException() {
            Assertions.assertThrows(NullPointerException.class, () -> registerAuthenticator.login(null));
        }
    }

    @Nested
    @DisplayName("Database Failures & Constraint Violations")
    class DatabaseFailureTests {

        @Test
        @DisplayName("Should fail when salt exceeds column capacity (VARCHAR 50 overflow)")
        void register_SaltOverflow_ThrowsRuntimeException() {
            String hugeSalt = "s".repeat(51);

            RuntimeException ex = Assertions.assertThrows(
                    RuntimeException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand("valid_user", "ValidPass1!", hugeSalt, "test@test.com")));

            Assertions.assertTrue(ex.getMessage().contains("Failed to save user"));
        }

        @Test
        @DisplayName("Should fail with RuntimeException if database connection is prematurely closed")
        void register_ClosedConnection_ThrowsRuntimeException() throws SQLException {
            Connection deadConnection =
                    DriverManager.getConnection("jdbc:h2:mem:register_testdb;DB_CLOSE_DELAY=-1", "sa", "");
            UserRepository deadRepo = new UserRepository(deadConnection);
            RegisterAuthenticator deadAuthenticator = new RegisterAuthenticator(deadRepo);

            deadConnection.close();

            RuntimeException ex = Assertions.assertThrows(
                    RuntimeException.class,
                    () -> deadAuthenticator.login(
                            new RegisterCommand("valid_user", "ValidPass1!", "salt", "test@test.com")));

            Assertions.assertTrue(ex.getMessage().contains("Failed to save user"));
        }

        @Test
        @DisplayName("Duplicate registration with identical casing should fail UNIQUE constraint")
        void register_DuplicateUsername_FailsUniqueConstraint() {
            registerAuthenticator.login(new RegisterCommand("unique_user", "ValidPass1!", "salt", "test@test.com"));

            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> registerAuthenticator.login(
                            new RegisterCommand("unique_user", "OtherPass123!", "salt2", "test2@test.com")));
        }
    }
}
