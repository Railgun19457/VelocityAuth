package io.github.railgun19457.velocityauth.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * 数据库连接池与方言适配（SQLite / MySQL）。
 */
public final class Database implements AutoCloseable {

    public enum Dialect { SQLITE, MYSQL }

    private final Dialect dialect;
    private final HikariDataSource dataSource;
    private final String description;
    private final Logger logger;

    private Database(Dialect dialect, HikariDataSource dataSource, String description, Logger logger) {
        this.dialect = dialect;
        this.dataSource = dataSource;
        this.description = description;
        this.logger = logger;
    }

    public static Database open(VelocityAuthConfig.Database config, Path dataDirectory, Logger logger)
            throws SQLException {
        Objects.requireNonNull(config, "config");
        return switch (config.type()) {
            case SQLITE -> openSqlite(config.sqlite(), dataDirectory, logger);
            case MYSQL -> openMysql(config.mysql(), logger);
        };
    }

    private static Database openSqlite(VelocityAuthConfig.Sqlite sqlite, Path dataDirectory, Logger logger)
            throws SQLException {
        Path file = Path.of(sqlite.file());
        if (!file.isAbsolute()) {
            file = dataDirectory.resolve(sqlite.file()).toAbsolutePath();
        }
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new SQLException("无法创建数据库目录 " + parent, e);
            }
        }
        String url = "jdbc:sqlite:" + file.toString().replace('\\', '/');

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("VelocityAuth-SQLite");
        hikari.setJdbcUrl(url);
        hikari.setDriverClassName("org.sqlite.JDBC");
        // SQLite 单写者：池大小 1 最稳，避免 database is locked
        hikari.setMaximumPoolSize(1);
        hikari.setConnectionInitSql("PRAGMA foreign_keys=ON");
        hikari.addDataSourceProperty("busy_timeout", "5000");
        hikari.addDataSourceProperty("journal_mode", "WAL");
        hikari.addDataSourceProperty("synchronous", "NORMAL");

        return new Database(Dialect.SQLITE, new HikariDataSource(hikari), "sqlite (" + file.getFileName() + ")",
                logger);
    }

    private static Database openMysql(VelocityAuthConfig.Mysql mysql, Logger logger) throws SQLException {
        String baseParams = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&useUnicode=true";
        ensureMysqlDatabase(mysql, baseParams);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("VelocityAuth-MySQL");
        hikari.setJdbcUrl("jdbc:mysql://" + mysql.host() + ":" + mysql.port() + "/" + mysql.database() + baseParams);
        hikari.setUsername(mysql.username());
        hikari.setPassword(mysql.password());
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10_000L);
        hikari.setMaxLifetime(1_800_000L);

        return new Database(Dialect.MYSQL, new HikariDataSource(hikari),
                "mysql (" + mysql.host() + ":" + mysql.port() + "/" + mysql.database() + ")", logger);
    }

    /** 若目标库不存在则自动创建，降低部署门槛。 */
    private static void ensureMysqlDatabase(VelocityAuthConfig.Mysql mysql, String baseParams) throws SQLException {
        String url = "jdbc:mysql://" + mysql.host() + ":" + mysql.port() + "/" + baseParams;
        try (Connection connection = DriverManager.getConnection(url, mysql.username(), mysql.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + mysql.database()
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new SQLException("无法连接或创建 MySQL 数据库 " + mysql.database()
                    + "（请确认账号有 CREATE 权限）", e);
        }
    }

    public Dialect dialect() {
        return dialect;
    }

    public String description() {
        return description;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 只读/单语句操作。 */
    public <T> T withConnection(SqlFunction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            return action.apply(connection);
        } catch (SQLException e) {
            throw new StorageException("数据库操作失败：" + e.getMessage(), e);
        }
    }

    /** 事务操作；抛出异常时回滚。 */
    public <T> T inTransaction(SqlFunction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = action.apply(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    logger.warn("[VelocityAuth] 事务回滚失败", rollbackFailure);
                }
                if (e instanceof SQLException sql) {
                    throw new StorageException("数据库事务失败：" + sql.getMessage(), sql);
                }
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new StorageException("数据库事务失败：" + e.getMessage(), e);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // 连接即将归还池中，忽略
                }
            }
        } catch (SQLException e) {
            throw new StorageException("无法获取数据库连接：" + e.getMessage(), e);
        }
    }

    public boolean ping() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
