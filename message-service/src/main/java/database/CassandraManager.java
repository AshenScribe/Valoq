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
import config.ServerConfig;
import java.net.InetSocketAddress;

public final class CassandraManager {

    private static volatile CqlSession session;

    private CassandraManager() {}

    public static synchronized void init(ServerConfig.DatabaseConfig config) {
        if (session != null && !session.isClosed()) {
            return;
        }

        session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(config.getContactPoint(), config.getPort()))
                .withLocalDatacenter(config.getLocalDatacenter())
                .withKeyspace(config.getKeyspace())
                .build();
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
