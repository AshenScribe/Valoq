package config;

public class ServerConfig {
    private int serverPort;
    private int databasePort;
    private String databaseHost;
    private String username;
    private String password;
    private String databaseName;
    private String sslMode;
    private String sslCertPath;
    private String sslKeyPath;
    private String sslRootCertPath;
    private long jwtExpirationTime;

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public void setDatabasePort(int databasePort) {
        this.databasePort = databasePort;
    }

    public void setDatabaseHost(String databaseHost) {
        this.databaseHost = databaseHost;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public void setJwtExpirationTime(long jwtExpirationTime) {
        this.jwtExpirationTime = jwtExpirationTime;
    }

    public ServerConfig() {}

    public int serverPort() {
        return serverPort;
    }

    public int databasePort() {
        return databasePort;
    }

    public String databaseHost() {
        return databaseHost;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String databaseName() {
        return databaseName;
    }

    public String sslMode() {
        return sslMode;
    }

    public String sslCertPath() {
        return sslCertPath;
    }

    public String sslKeyPath() {
        return sslKeyPath;
    }

    public String sslRootCertPath() {
        return sslRootCertPath;
    }

    public long jwtExpirationTime() {
        return jwtExpirationTime;
    }
}
