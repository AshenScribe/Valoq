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
package jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class JwtUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile JwtUtil instance;
    private PublicKey publicKey;

    private JwtUtil() {}

    public static JwtUtil getInstance() {
        if (instance == null) {
            synchronized (JwtUtil.class) {
                if (instance == null) {
                    instance = new JwtUtil();
                }
            }
        }
        return instance;
    }

    public synchronized void init(String publicKeyBase64) throws Exception {
        String cleanBase64 = publicKeyBase64.trim().replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleanBase64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        this.publicKey = keyFactory.generatePublic(spec);
    }

    public JsonNode decodeToPayload(String token) throws Exception {
        if (this.publicKey == null) {
            throw new IllegalStateException(
                    "JwtUtil is not initialized. Call init(publicKey) first.");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT token format.");
        }

        String header = parts[0];
        String payload = parts[1];
        byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

        byte[] signedContent = (header + "." + payload).getBytes(StandardCharsets.UTF_8);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(this.publicKey);
        verifier.update(signedContent);

        if (!verifier.verify(signatureBytes)) {
            throw new IllegalArgumentException("Invalid JWT signature.");
        }

        byte[] decodedPayloadBytes = Base64.getUrlDecoder().decode(payload);
        return MAPPER.readTree(decodedPayloadBytes);
    }
}
