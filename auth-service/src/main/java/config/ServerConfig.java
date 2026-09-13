package config;

public class ServerConfig {
    private int serverPort;
    private String host;
    private int databasePort;
    private String username;
    private String password;
    private String databaseName;
    private String sslMode;
    private String sslCertPath;
    private String sslKeyPath;
    private String sslRootCertPath;
    private long jwtExpirationTime;

    public ServerConfig() {}

    public int serverPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String host() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int databasePort() {
        return databasePort;
    }

    public void setDatabasePort(int databasePort) {
        this.databasePort = databasePort;
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String password() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String databaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String sslMode() {
        return sslMode;
    }

    public void setSslMode(String sslMode) {
        this.sslMode = sslMode;
    }

    public String sslCertPath() {
        return sslCertPath;
    }

    public void setSslCertPath(String sslCertPath) {
        this.sslCertPath = sslCertPath;
    }

    public String sslKeyPath() {
        return sslKeyPath;
    }

    public void setSslKeyPath(String sslKeyPath) {
        this.sslKeyPath = sslKeyPath;
    }

    public String sslRootCertPath() {
        return sslRootCertPath;
    }

    public void setSslRootCertPath(String sslRootCertPath) {
        this.sslRootCertPath = sslRootCertPath;
    }

    public long jwtExpirationTime() {
        return jwtExpirationTime;
    }

    public void setJwtExpirationTime(long jwtExpirationTime) {
        this.jwtExpirationTime = jwtExpirationTime;
    }
}
