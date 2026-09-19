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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

public final class JwtUtil {

    private static final JwtUtil INSTANCE = new JwtUtil();

    private final PrivateKey privateKey = KeyProvider.getInstance().getPrivateKey();
    private final PublicKey publicKey = KeyProvider.getInstance().getPublicKey();

    private long expirationTimeSecond;

    public static JwtUtil getInstance() {
        return INSTANCE;
    }

    public static void getInstance(long expirationTimeSecond) {
        INSTANCE.expirationTimeSecond = expirationTimeSecond;
    }

    public String generateJwt(String subject, Map<String, Object> claims) {
        if (subject == null) throw new RuntimeException("Subject cannot be null");
        try {
            Date now = new Date();
            Date expiry = new Date(now.getTime() + expirationTimeSecond * 1000);

            return Jwts.builder()
                    .subject(subject)
                    .claims(claims)
                    .issuedAt(now)
                    .expiration(expiry)
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    public String generateJwt(String subject, long expirationMillis, Map<String, Object> claims) {
        if (subject == null) throw new RuntimeException("Subject cannot be null");
        try {
            Date now = new Date();
            Date expiry = new Date(now.getTime() + expirationMillis);

            return Jwts.builder()
                    .subject(subject)
                    .claims(claims)
                    .issuedAt(now)
                    .expiration(expiry)
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    public Optional<Claims> parseJwt(String jwt) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
