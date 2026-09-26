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
package authentication;

import authenticator.BasicAuthenticator;
import authenticator.RegisterAuthenticator;
import authenticator.jwt.JwtUtil;
import base.BaseIntegrationTest;
import database.UserRepository;
import database.entity.UserEntity;
import exception.ValidationException;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.command.AuthCommand;
import server.command.BasicCommand;
import server.command.RegisterCommand;

class RegisterAuthenticatorTest extends BaseIntegrationTest {

    private RegisterAuthenticator registerAuthenticator;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = new UserRepository();
        registerAuthenticator = new RegisterAuthenticator(userRepository);
    }

    @Nested
    @DisplayName("Successful Registration Flow")
    class SuccessTests {

        @Test
        @DisplayName(
                "Should successfully register a new user, persist to database, and return valid JWT")
        void register_ValidUser_Success() {
            String username = "valid_user";
            String password = "StrongPassword123!";
            String salt = "random_salt_123";
            String email = "valid_user@example.com";

            String token =
                    registerAuthenticator.login(
                            new RegisterCommand(username, password, salt, email));
            Assertions.assertNotNull(token, "JWT token should not be null");
            Optional<Claims> claimsOpt = JwtUtil.getInstance().parseJwt(token);
            Assertions.assertTrue(
                    claimsOpt.isPresent(), "Generated JWT should be valid and parseable");

            String tokenSubject = claimsOpt.get().getSubject();
            Assertions.assertTrue(
                    tokenSubject.startsWith("usr_"), "Subject must be the generated user ID");
            UserEntity savedUser = userRepository.getUser(username);
            Assertions.assertNotNull(savedUser, "User must be found in database");
            Assertions.assertEquals(tokenSubject, savedUser.userId());
            Assertions.assertEquals(username, savedUser.username());
            Assertions.assertEquals(password, savedUser.passwordHash());
            Assertions.assertEquals(salt, savedUser.salt());
        }

        @Test
        @DisplayName(
                "Newly registered user should immediately be able to log in via BasicAuthenticator")
        void register_ThenBasicLogin_Success() {
            String username = "quick_login_user";
            String password = "SecurePassword1!";
            String salt = "salt_xyz";

            registerAuthenticator.login(
                    new RegisterCommand(username, password, salt, "user@domain.com"));

            BasicAuthenticator basicAuthenticator = new BasicAuthenticator(userRepository);
            String loginToken =
                    basicAuthenticator.login(new BasicCommand(username, password, salt));

            Assertions.assertNotNull(loginToken);
            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(loginToken).isPresent());
        }
    }

    @Nested
    @DisplayName("Username Validation Edge Cases")
    class UsernameValidationTests {

        @ParameterizedTest
        @ValueSource(strings = {"a", "ab"})
        @DisplayName("Should reject usernames shorter than 3 characters")
        void register_UsernameTooShort_ThrowsValidationException(String shortUsername) {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    shortUsername,
                                                    "StrongPassword1!",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals(
                    "Username is too short, must be at least 3 characters", ex.getMessage());
        }

        @Test
        @DisplayName("Should reject usernames longer than 32 characters")
        void register_UsernameTooLong_ThrowsValidationException() {
            String longUsername = "a".repeat(33);

            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    longUsername,
                                                    "StrongPassword1!",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals(
                    "Username is too long, must be less than 32 characters", ex.getMessage());
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"user@name", "user name", "user-name", "user.name", "user#123", "user!"})
        @DisplayName(
                "Should reject usernames containing characters other than alphanumeric and underscore")
        void register_UsernameInvalidCharacters_ThrowsValidationException(String invalidUsername) {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    invalidUsername,
                                                    "StrongPassword1!",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals("Username contains invalid characters", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Password Validation Edge Cases")
    class PasswordValidationTests {

        @Test
        @DisplayName("Should reject passwords shorter than 8 characters")
        void register_PasswordTooShort_ThrowsValidationException() {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    "valid_user", "Short1!", "salt", "a@b.com")));

            Assertions.assertEquals(
                    "Password is too short, must be at least 8 characters", ex.getMessage());
        }

        @Test
        @DisplayName("Should reject passwords longer than 64 characters")
        void register_PasswordTooLong_ThrowsValidationException() {
            String longPassword = "A1!" + "a".repeat(65);

            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    "valid_user",
                                                    longPassword,
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals(
                    "Password is too long, must be less than 64 characters", ex.getMessage());
        }

        @Test
        @DisplayName("Should reject password missing uppercase letters")
        void register_PasswordNoUppercase_ThrowsValidationException() {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    "valid_user",
                                                    "lowercase1!",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals(
                    "Password must contain at least one uppercase letter", ex.getMessage());
        }

        @Test
        @DisplayName("Should reject password missing lowercase letters")
        void register_PasswordNoLowercase_ThrowsValidationException() {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    "valid_user",
                                                    "UPPERCASE1!",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals(
                    "Password must contain at least one lowercase letter", ex.getMessage());
        }

        @Test
        @DisplayName("Should reject password missing numbers")
        void register_PasswordNoNumber_ThrowsValidationException() {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    "valid_user",
                                                    "NoNumberSpecial!",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals("Password must contain at least one number", ex.getMessage());
        }

        @Test
        @DisplayName("Should reject password missing special characters")
        void register_PasswordNoSpecialChar_ThrowsValidationException() {
            ValidationException ex =
                    Assertions.assertThrows(
                            ValidationException.class,
                            () ->
                                    registerAuthenticator.login(
                                            new RegisterCommand(
                                                    "valid_user",
                                                    "NoSpecial123",
                                                    "salt",
                                                    "a@b.com")));

            Assertions.assertEquals(
                    "Password must contain at least one special character (!@#$%^&*()-_+=<>?)",
                    ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Database Constraints & Safety")
    class DatabaseConstraintTests {

        @Test
        @DisplayName("Should fail when attempting to register an already existing username")
        void register_DuplicateUsername_ThrowsException() {
            String username = "existing_user";
            registerAuthenticator.login(
                    new RegisterCommand(username, "StrongPass1!", "salt1", "a@b.com"));
            Assertions.assertThrows(
                    RuntimeException.class,
                    () ->
                            registerAuthenticator.login(
                                    new RegisterCommand(
                                            username, "StrongPass2@", "salt2", "c@d.com")));
        }

        @Test
        @DisplayName("Should throw ClassCastException when passed an incompatible AuthCommand type")
        void register_InvalidCommandType_ThrowsClassCastException() {
            AuthCommand invalidCommand = new AuthCommand() {};

            Assertions.assertThrows(
                    ClassCastException.class, () -> registerAuthenticator.login(invalidCommand));
        }
    }
}
