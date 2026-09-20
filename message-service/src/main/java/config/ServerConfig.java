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

    private DatabaseConfig database = new DatabaseConfig();
    private ServerSettings server = new ServerSettings();

    public ServerConfig() {}

    public DatabaseConfig getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }

    public ServerSettings getServer() {
        return server;
    }

    public void setServer(ServerSettings server) {
        this.server = server;
    }

    public String databaseHost() {
        return database != null ? database.getHost() : null;
    }

    public int databasePort() {
        return database != null ? database.getPort() : 0;
    }

    public int serverPort() {
        return server != null ? server.getPort() : 0;
    }

    public static class DatabaseConfig {
        private String host = "localhost";
        private int port = 9042;
        private String contactPoint;
        private String localDatacenter = "datacenter1";
        private String keyspace = "valoq_messages";
        private String username;
        private String password;
        private SslConfig ssl = new SslConfig();

        public DatabaseConfig() {}

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getContactPoint() {
            return contactPoint != null ? contactPoint : host;
        }
        public void setContactPoint(String contactPoint) { this.contactPoint = contactPoint; }

        public String getLocalDatacenter() { return localDatacenter; }
        public void setLocalDatacenter(String localDatacenter) { this.localDatacenter = localDatacenter; }

        public String getKeyspace() { return keyspace; }
        public void setKeyspace(String keyspace) { this.keyspace = keyspace; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public SslConfig getSsl() { return ssl; }
        public void setSsl(SslConfig ssl) { this.ssl = ssl; }
    }

    public static class SslConfig {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class ServerSettings {
        private int port = 8002;

        public ServerSettings() {}

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }
}
