package authenticator.jwt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class KeyProvider {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private static final KeyProvider INSTANCE;

    static {
        try {
            INSTANCE = new KeyProvider();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private KeyProvider() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        this.privateKey = loadPrivateKey();
        this.publicKey = loadPublicKey();
    }

    public static KeyProvider getInstance() {
        return INSTANCE;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    private PrivateKey loadPrivateKey() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        String base64Str = readClasspathResource("private.key");
        byte[] keyBytes = Base64.getDecoder().decode(base64Str.trim());

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private PublicKey loadPublicKey() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        String base64Str = readClasspathResource("public.key");
        byte[] keyBytes = Base64.getDecoder().decode(base64Str.trim());

        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private String readClasspathResource(String fileName) throws IOException {
        try (InputStream is = JwtUtil.class.getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IllegalStateException("Key file not found on classpath: " + fileName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
