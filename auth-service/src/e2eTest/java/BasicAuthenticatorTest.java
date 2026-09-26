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
import authenticator.BasicAuthenticator;
import authenticator.jwt.JwtUtil;
import base.BaseIntegrationTest;
import exception.UserNotFoundException;
import java.sql.SQLException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import server.command.BasicCommand;
import server.model.User;

class BasicAuthenticatorTest extends BaseIntegrationTest {

    private final BasicAuthenticator basicAuthenticator = new BasicAuthenticator();
    private User defaultUser;

    @BeforeEach
    void setUpUser() throws SQLException {
        defaultUser = users().createUser("testuser", "testpasswordhash", "testsalt");
    }

    @Test
    @DisplayName("Should successfully authenticate valid credentials and issue parseable JWT")
    void login_ValidCredentials_ReturnsValidJwt() {
        String token =
                basicAuthenticator.login(
                        new BasicCommand(
                                defaultUser.username(),
                                defaultUser.passwordHash(),
                                defaultUser.salt()));

        Assertions.assertNotNull(token, "JWT token should not be null");
        Assertions.assertTrue(
                JwtUtil.getInstance().parseJwt(token).isPresent(),
                "Generated JWT should be parseable and valid");
    }

    @Test
    @DisplayName("Should authenticate correctly when multiple distinct users exist in the database")
    void login_MultipleUsersInDb_AuthenticatesCorrectTargetUser() throws SQLException {
        User otherUser = users().createUser("otheruser", "otherhash", "othersalt");

        String token =
                basicAuthenticator.login(
                        new BasicCommand(
                                otherUser.username(), otherUser.passwordHash(), otherUser.salt()));

        Assertions.assertTrue(JwtUtil.getInstance().parseJwt(token).isPresent());
    }

    @Test
    @DisplayName("Should fail authentication when username does not exist")
    void login_NonExistentUsername_ThrowsUserNotFoundException() {
        UserNotFoundException ex =
                Assertions.assertThrows(
                        UserNotFoundException.class,
                        () ->
                                basicAuthenticator.login(
                                        new BasicCommand(
                                                "non_existent_user",
                                                "testpasswordhash",
                                                "testsalt")));

        Assertions.assertEquals("User with username non_existent_user not found", ex.getMessage());
    }

    @Test
    @DisplayName("Should fail authentication when password hash is incorrect")
    void login_IncorrectPassword_ThrowsUserNotFoundException() {
        UserNotFoundException ex =
                Assertions.assertThrows(
                        UserNotFoundException.class,
                        () ->
                                basicAuthenticator.login(
                                        new BasicCommand(
                                                defaultUser.username(),
                                                "wrong_password_hash",
                                                defaultUser.salt())));

        Assertions.assertEquals(
                "User with username testuser not found or password does not match",
                ex.getMessage());
    }

    @ParameterizedTest
    @DisplayName("Should fail authentication when username is null or blank")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void login_NullOrBlankUsername_ThrowsUserNotFoundException(String invalidUsername) {
        Assertions.assertThrows(
                UserNotFoundException.class,
                () ->
                        basicAuthenticator.login(
                                new BasicCommand(invalidUsername, "testpasswordhash", "testsalt")));
    }

    @ParameterizedTest
    @CsvSource({
        "testuser, WRONG_HASH, testsalt",
        "TESTUSER, testpasswordhash, testsalt",
        "testuser, TESTPASSWORDHASH, testsalt"
    })
    @DisplayName("Should enforce case sensitivity on credentials")
    void login_CaseMismatch_ThrowsUserNotFoundException(
            String username, String passwordHash, String salt) {
        Assertions.assertThrows(
                UserNotFoundException.class,
                () -> basicAuthenticator.login(new BasicCommand(username, passwordHash, salt)));
    }
}
