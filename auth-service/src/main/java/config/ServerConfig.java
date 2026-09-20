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
package config;

public class ServerConfig {
    private ServerProps server = new ServerProps();
    private DatabaseProps database = new DatabaseProps();
    private JwtProps jwt = new JwtProps();

    public ServerConfig() {}

    public ServerProps server() {
        return server;
    }

    public ServerProps getServer() {
        return server;
    }

    public void setServer(ServerProps server) {
        this.server = server;
    }

    public DatabaseProps database() {
        return database;
    }

    public DatabaseProps getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseProps database) {
        this.database = database;
    }

    public JwtProps jwt() {
        return jwt;
    }

    public JwtProps getJwt() {
        return jwt;
    }

    public void setJwt(JwtProps jwt) {
        this.jwt = jwt;
    }

    public static class ServerProps {
        private String host = "localhost";
        private int port = 8001;
        private ServerSslProps ssl = new ServerSslProps();

        public String host() {
            return host;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int port() {
            return port;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public ServerSslProps ssl() {
            return ssl;
        }

        public ServerSslProps getSsl() {
            return ssl;
        }

        public void setSsl(ServerSslProps ssl) {
            this.ssl = ssl;
        }
    }

    public static class ServerSslProps {
        private boolean enabled = false;
        private String certChainPath;
        private String privateKeyPath;
        private String keyPassword;

        public boolean isEnabled() {
            return enabled;
        }

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String certChainPath() {
            return certChainPath;
        }

        public String getCertChainPath() {
            return certChainPath;
        }

        public void setCertChainPath(String certChainPath) {
            this.certChainPath = certChainPath;
        }

        public String privateKeyPath() {
            return privateKeyPath;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String keyPassword() {
            return keyPassword;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }
    }

    public static class DatabaseProps {
        private String host = "localhost";
        private int port = 5432;
        private String name = "valoq";
        private String username = "valoq";
        private String password = "valoq";
        private String sslMode = "disable";
        private DatabaseSslProps ssl = new DatabaseSslProps();

        public String host() {
            return host;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int port() {
            return port;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String name() {
            return name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String username() {
            return username;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String password() {
            return password;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String sslMode() {
            return sslMode;
        }

        public String getSslMode() {
            return sslMode;
        }

        public void setSslMode(String sslMode) {
            this.sslMode = sslMode;
        }

        public DatabaseSslProps ssl() {
            return ssl;
        }

        public DatabaseSslProps getSsl() {
            return ssl;
        }

        public void setSsl(DatabaseSslProps ssl) {
            this.ssl = ssl;
        }
    }

    public static class DatabaseSslProps {
        private String mode = "disable";
        private String certPath;
        private String keyPath;
        private String rootCertPath;
        private String keyPassword;

        public String mode() {
            return mode;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String certPath() {
            return certPath;
        }

        public String getCertPath() {
            return certPath;
        }

        public void setCertPath(String certPath) {
            this.certPath = certPath;
        }

        public String keyPath() {
            return keyPath;
        }

        public String getKeyPath() {
            return keyPath;
        }

        public void setKeyPath(String keyPath) {
            this.keyPath = keyPath;
        }

        public String rootCertPath() {
            return rootCertPath;
        }

        public String getRootCertPath() {
            return rootCertPath;
        }

        public void setRootCertPath(String rootCertPath) {
            this.rootCertPath = rootCertPath;
        }

        public String keyPassword() {
            return keyPassword;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }
    }

    public static class JwtProps {
        private long expirationSeconds = 300;

        public long expirationSeconds() {
            return expirationSeconds;
        }

        public long getExpirationSeconds() {
            return expirationSeconds;
        }

        public void setExpirationSeconds(long expirationSeconds) {
            this.expirationSeconds = expirationSeconds;
        }
    }
}
