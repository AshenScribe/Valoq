package authenticator.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.Map;

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

    public Claims parseJwt(String jwt) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JWT", e);
        }
    }
}
