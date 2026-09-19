package database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import config.ServerConfig;
import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseManager {

    private static volatile HikariDataSource dataSource;

    private DatabaseManager() {}

    public static synchronized void init(ServerConfig config) {
        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }

        HikariConfig hikariConfig = new HikariConfig();

        String jdbcUrl = String.format(
                "jdbc:postgresql://%s:%d/%s", config.databaseHost(), config.databasePort(), config.databaseName());

        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setDriverClassName("org.postgresql.Driver");

        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setIdleTimeout(300_000);
        hikariConfig.setConnectionTimeout(5_000);
        hikariConfig.setMaxLifetime(1_800_000);

        hikariConfig.setLeakDetectionThreshold(2000);
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(hikariConfig);
    }

    public static Connection getConnection() throws SQLException {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.isClosed()) {
            throw new IllegalStateException("DatabaseManager is not initialized or pool is closed.");
        }
        return ds.getConnection();
    }

    public static synchronized void close() {
        if (dataSource != null) {
            if (!dataSource.isClosed()) {
                dataSource.close();
            }
            dataSource = null;
        }
    }
}
