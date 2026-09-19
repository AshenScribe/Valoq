package server;

import authenticator.jwt.JwtUtil;
import base.BaseIntegrationTest;
import client.AuthTestClient;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.model.User;

class AuthServerTest extends BaseIntegrationTest {

    @Test
    void testPublicKeyLoading() throws Exception {
        try (AuthTestClient client = createClient()) {
            String response = client.getPublicKey();
            Assertions.assertFalse(response.isBlank());
        }
    }

    @Test
    void testBasicAuthenticationFlow() throws Exception {
        User user = users().createUser("john", "hashed_pwd", "salt123");

        try (AuthTestClient client = createClient()) {
            String jwt = client.loginBasic(user.username(), user.passwordHash(), user.salt());

            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(jwt).isPresent());
        }
    }

    @Test
    @DisplayName("Should authenticate using an existing JWT via AUTH TOKEN")
    void testTokenAuthenticationFlow() throws Exception {
        User user = users().createUser("token_user", "pwd_hash", "salt");

        try (AuthTestClient client = createClient()) {
            String jwt = client.loginBasic(user.username(), user.passwordHash(), user.salt());
            String renewedToken = client.loginToken(jwt);
            Assertions.assertNotNull(renewedToken);
            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(renewedToken).isPresent());
        }
    }

    @Test
    @DisplayName("Public key returned by server should verify the JWT signature externally")
    void testPublicKeyVerifiesIssuedToken() throws Exception {
        User user = users().createUser("crypto_user", "pwd_hash", "salt");

        try (AuthTestClient client = createClient()) {
            String base64PublicKey = client.getPublicKey();
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey.trim());
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            String token = client.loginBasic(user.username(), user.passwordHash(), user.salt());
            Assertions.assertDoesNotThrow(() -> {
                Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
            });
        }
    }

    @Test
    @DisplayName("Should process multiple sequential commands over the same TCP connection")
    void testMultipleCommandsOnSameConnection() throws Exception {
        User user1 = users().createUser("seq_user1", "pwd1", "salt1");
        User user2 = users().createUser("seq_user2", "pwd2", "salt2");

        try (AuthTestClient client = createClient()) {
            String pubKey = client.getPublicKey();
            Assertions.assertFalse(pubKey.isBlank());
            String jwt1 = client.loginBasic(user1.username(), user1.passwordHash(), user1.salt());
            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(jwt1).isPresent());
            String jwt2 = client.loginBasic(user2.username(), user2.passwordHash(), user2.salt());
            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(jwt2).isPresent());
            Assertions.assertNotEquals(jwt1, jwt2);
        }
    }

    @Test
    @DisplayName("Should handle multiple concurrent client connections simultaneously")
    void testConcurrentClientLogins() {
        int clientCount = 10;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            final int index = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    User user = users().createUser("concurrent_" + index, "pwd", "salt");
                    try (AuthTestClient client = createClient()) {
                        String jwt = client.loginBasic(user.username(), user.passwordHash(), user.salt());
                        Assertions.assertTrue(
                                JwtUtil.getInstance().parseJwt(jwt).isPresent());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    @Test
    void testLoginWithInvalidPasswordReturnsError() throws Exception {
        User user = users().createUser("bob", "correct_pwd", "salt");

        try (AuthTestClient client = createClient()) {
            String response = client.loginBasic(user.username(), "wrong_pwd", user.salt());
            Assertions.assertTrue(response.startsWith("ERROR"));
        }
    }

    @Test
    @DisplayName("Should register over TCP, return valid JWT, and allow login over the same connection")
    void testRegisterAndLoginOverNetty() throws Exception {
        String username = "netty_registered_user";
        String password = "SecurePassword99!";
        String salt = "salt123";

        try (AuthTestClient client = createClient()) {
            String registerJwt = client.register(username, password, salt, "user@test.com");
            Assertions.assertNotNull(registerJwt);
            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(registerJwt).isPresent());
            String loginJwt = client.loginBasic(username, password, salt);
            Assertions.assertNotNull(loginJwt);
            Assertions.assertTrue(JwtUtil.getInstance().parseJwt(loginJwt).isPresent());
        }
    }

    @Test
    @DisplayName("Should return ERROR prefix over TCP when password fails validation")
    void testRegisterWithWeakPasswordReturnsErrorOverNetty() throws Exception {
        try (AuthTestClient client = createClient()) {
            String response = client.register("valid_user", "weak", "salt", "test@test.com");
            Assertions.assertTrue(response.startsWith("ERROR"));
            Assertions.assertTrue(response.contains("Password is too short"));
        }
    }
}
