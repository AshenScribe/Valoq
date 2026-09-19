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
