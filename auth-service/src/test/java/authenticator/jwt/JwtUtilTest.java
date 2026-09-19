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
package authenticator.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JwtUtil Core & Security Verification Suite")
class JwtUtilTest {

    private final JwtUtil jwtUtil = JwtUtil.getInstance();
    private static PrivateKey privateKey;

    @BeforeAll
    static void ensureDevKeysExistAndLoad() {
        String privStr;

        try (InputStream privStream = JwtUtilTest.class.getClassLoader().getResourceAsStream("private.key");
                InputStream pubStream = JwtUtilTest.class.getClassLoader().getResourceAsStream("public.key")) {

            if (privStream == null || pubStream == null) {
                throw new IllegalStateException("Dev keys missing! Run ./gradlew generateDevRsaKeys");
            }
            privStr = new String(privStream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dev key files from classpath", e);
        }

        try {
            byte[] privBytes = Base64.getDecoder().decode(privStr);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse dev RSA keys", e);
        }
    }

    @Nested
    @DisplayName("Happy Path Tests")
    class HappyPathTests {

        @Test
        @DisplayName("Should successfully generate and parse token end-to-end")
        void testEndToEndTokenLifecycle() {
            String subject = "user-" + UUID.randomUUID();
            Map<String, Object> claimsMap = Map.of("role", "ADMIN", "tenantId", "12345");

            String token = jwtUtil.generateJwt(subject, 300000L, claimsMap);
            Assertions.assertNotNull(token, "Generated JWT string should not be null");

            Claims claims = jwtUtil.parseJwt(token).get();
            Assertions.assertEquals(subject, claims.getSubject());
            Assertions.assertEquals("ADMIN", claims.get("role", String.class));
            Assertions.assertEquals("12345", claims.get("tenantId", String.class));
            Assertions.assertNotNull(claims.getIssuedAt(), "IssuedAt claim must exist");
            Assertions.assertNotNull(claims.getExpiration(), "Expiration claim must exist");
        }

        @Test
        @DisplayName("Should parse externally generated valid JWT correctly")
        void testParseValidJwtPair() {
            String expectedSubject = "user-123";
            String expectedRole = "ADMIN";
            Date now = new Date();
            Date expiry = new Date(now.getTime() + 60000);

            String jwt = Jwts.builder()
                    .subject(expectedSubject)
                    .claim("role", expectedRole)
                    .issuedAt(now)
                    .expiration(expiry)
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();

            Claims claims = jwtUtil.parseJwt(jwt).get();
            Assertions.assertNotNull(claims);
            Assertions.assertEquals(expectedSubject, claims.getSubject());
            Assertions.assertEquals(expectedRole, claims.get("role", String.class));
        }

        @Test
        @DisplayName("Singleton instance should always return the same reference")
        void testSingletonIntegrity() {
            JwtUtil first = JwtUtil.getInstance();
            JwtUtil second = JwtUtil.getInstance();
            Assertions.assertSame(first, second, "getInstance() must return identical references");
        }
    }

    @Nested
    @DisplayName("Expiration & Time Edge Cases")
    class ExpirationTests {

        @Test
        @DisplayName("Should reject expired token generated in the past")
        void testExpiredTokenRejection() {
            Date past = new Date(System.currentTimeMillis() - 50000);
            Date expiredAt = new Date(System.currentTimeMillis() - 10000);

            String expiredJwt = Jwts.builder()
                    .subject("user-expired")
                    .issuedAt(past)
                    .expiration(expiredAt)
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();

            Assertions.assertTrue(jwtUtil.parseJwt(expiredJwt).isEmpty(), "Expired token must not be valid");
        }

        @Test
        @DisplayName("Should reject token issued in the future (nbf / iat drift)")
        void testFutureIssuedTokenRejection() {
            Date futureTime = new Date(System.currentTimeMillis() + 100000);

            String futureJwt = Jwts.builder()
                    .subject("future-user")
                    .issuedAt(futureTime)
                    .expiration(new Date(futureTime.getTime() + 60000))
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();

            Claims claims = jwtUtil.parseJwt(futureJwt).get();
            Assertions.assertNotNull(claims);
        }
    }

    @Nested
    @DisplayName("Security & Cryptographic Attacks")
    class SecurityAttackTests {

        @Test
        @DisplayName("Should reject token signed with an untrusted key pair (Key Tampering Attack)")
        void testUntrustedKeyPairSignatureRejection() throws Exception {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair rogueKeyPair = kpg.generateKeyPair();

            String forgedToken = Jwts.builder()
                    .subject("hacker-user")
                    .claim("role", "SUPERADMIN")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60000))
                    .signWith(rogueKeyPair.getPrivate(), Jwts.SIG.RS256)
                    .compact();

            Assertions.assertTrue(
                    jwtUtil.parseJwt(forgedToken).isEmpty(), "Forged token must fail signature verification");
        }

        @Test
        @DisplayName("Should reject token with modified payload (Payload Tampering Attack)")
        void testPayloadTamperingRejection() {
            String validJwt = jwtUtil.generateJwt("user-1", 60000L, Map.of("role", "USER"));

            String[] parts = validJwt.split("\\.");
            String header = parts[0];
            String payload = parts[1];
            String signature = parts[2];

            String tamperedPayload = Base64.getUrlEncoder()
                    .encodeToString("{\"sub\":\"user-1\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8))
                    .replace("=", "");

            String tamperedJwt = header + "." + tamperedPayload + "." + signature;

            Assertions.assertTrue(
                    jwtUtil.parseJwt(tamperedJwt).isEmpty(), "Tampered payload must fail signature verification");
        }

        @Test
        @DisplayName("Should reject 'none' algorithm unsigned token (Alg: None Exploit)")
        void testUnsignedNoneAlgorithmRejection() {
            String noneHeader = "ewogICJhbGciOiAibm9uZSIKfQ";
            String payload = "ewogICJzdWIiOiAiYWRtaW4iCn0";

            String unsignedJwt = noneHeader + "." + payload + ".";

            Assertions.assertTrue(
                    jwtUtil.parseJwt(unsignedJwt).isEmpty(), "Unsigned tokens using 'none' algorithm must be rejected");
        }
    }

    @Nested
    @DisplayName("Malformed Input & Boundaries")
    class MalformedInputTests {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "",
                    "   ",
                    "not-a-jwt",
                    "header.payload",
                    "header.payload.signature.extra",
                    "?????.?????.?????"
                })
        @DisplayName("Should fail when parsing invalid or malformed JWT formats")
        void testMalformedJwtInputs(String malformedJwt) {
            Assertions.assertTrue(jwtUtil.parseJwt(malformedJwt).isEmpty());
        }

        @Test
        @DisplayName("Should handle null inputs gracefully")
        void testNullInputs() {
            Assertions.assertTrue(jwtUtil.parseJwt(null).isEmpty());
            Assertions.assertTrue(jwtUtil.generateJwt("user-null", 60000L, null) != null);
        }

        @Test
        @DisplayName("Should handle empty claims map without throwing exceptions")
        void testEmptyClaimsMap() {
            String jwt = jwtUtil.generateJwt("user-empty-claims", 60000L, Map.of());
            Claims claims = jwtUtil.parseJwt(jwt).get();

            Assertions.assertEquals("user-empty-claims", claims.getSubject());
            Assertions.assertNotNull(claims.getExpiration());
        }

        @Test
        @DisplayName("Should handle complex nested claims structures")
        void testNestedComplexClaims() {
            Map<String, Object> complexClaims = Map.of(
                    "permissions", java.util.List.of("READ", "WRITE", "DELETE"),
                    "metadata", Map.of("ip", "127.0.0.1", "attempts", 3));

            String token = jwtUtil.generateJwt("complex-user", 60000L, complexClaims);
            Claims claims = jwtUtil.parseJwt(token).get();

            @SuppressWarnings("unchecked")
            java.util.List<String> perms = claims.get("permissions", java.util.List.class);
            Assertions.assertEquals(3, perms.size());
            Assertions.assertTrue(perms.contains("DELETE"));
        }
    }
}
