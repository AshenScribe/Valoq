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
package database;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import config.ServerConfig;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class CassandraManager {

    private static volatile CqlSession session;

    private CassandraManager() {}

    public static synchronized void init(ServerConfig.DatabaseConfig config) {
        if (session != null && !session.isClosed()) {
            return;
        }

        String host = config.getContactPoint() != null ? config.getContactPoint() : config.getHost();
        String datacenter = config.getLocalDatacenter() != null ? config.getLocalDatacenter() : "datacenter1";

        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(host, config.getPort()))
                .withLocalDatacenter(datacenter);

        if (config.getKeyspace() != null && !config.getKeyspace().isBlank()) {
            builder.withKeyspace(config.getKeyspace());
        }

        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            builder.withAuthCredentials(config.getUsername(), config.getPassword());
        }

        if (config.getSsl() != null && config.getSsl().isEnabled()) {
            builder.withSslContext(createDevSslContext());
        }

        session = builder.build();
    }

    private static SSLContext createDevSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSLContext for Cassandra", e);
        }
    }

    public static CqlSession getSession() {
        CqlSession s = session;
        if (s == null || s.isClosed()) {
            throw new IllegalStateException("CassandraManager is not initialized or session is closed.");
        }
        return s;
    }

    public static synchronized void close() {
        if (session != null) {
            if (!session.isClosed()) {
                session.close();
            }
            session = null;
        }
    }

    public static boolean isInitialized() {
        return session != null && !session.isClosed();
    }
}
