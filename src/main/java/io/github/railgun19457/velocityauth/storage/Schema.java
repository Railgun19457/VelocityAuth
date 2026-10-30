package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.Database.Dialect;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 建表与迁移。schema 版本记录在 {@code va_meta} 表。
 * <p>
 * 迁移按版本号顺序追加；新版本只允许在末尾追加，不得修改历史步骤。
 */
public final class Schema {

    /** 当前期望的 schema 版本。 */
    public static final int CURRENT_VERSION = 3;

    private Schema() {
    }

    public static void migrate(Database database, Logger logger) {
        database.inTransaction(connection -> {
            ensureMetaTable(connection, database.dialect());
            int current = readVersion(connection);
            if (current > CURRENT_VERSION) {
                throw new StorageException("数据库 schema 版本 " + current + " 高于插件支持的 " + CURRENT_VERSION
                        + "，请升级插件或使用备份数据库");
            }
            if (current == CURRENT_VERSION) {
                return null;
            }
            boolean fresh = current == 0;
            // 全新库从 v1 起逐版本应用，保证初始结构与后续迁移完全一致（避免"建 v1 表却标记为最新版"）
            for (int version = current + 1; version <= CURRENT_VERSION; version++) {
                applyMigration(connection, database.dialect(), version);
                writeVersion(connection, database.dialect(), version);
                logger.info("[VelocityAuth] 已应用数据库迁移 v{}", version);
            }
            if (fresh) {
                logger.info("[VelocityAuth] 已初始化数据库结构（schema v{}）", CURRENT_VERSION);
            }
            return null;
        });
    }

    private static void ensureMetaTable(Connection connection, Dialect dialect) throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "CREATE TABLE IF NOT EXISTS `va_meta` (`key` VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + " `value` VARCHAR(255) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                : "CREATE TABLE IF NOT EXISTS va_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM va_meta WHERE key = ?")) {
            ps.setString(1, "schema_version");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return Integer.parseInt(rs.getString(1));
                    } catch (NumberFormatException e) {
                        throw new StorageException("va_meta.schema_version 不是合法整数", e);
                    }
                }
            }
        }
        return 0;
    }

    private static void writeVersion(Connection connection, Dialect dialect, int version) throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "INSERT INTO `va_meta` (`key`, `value`) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)"
                : "INSERT INTO va_meta (key, value) VALUES (?, ?)"
                        + " ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "schema_version");
            ps.setString(2, Integer.toString(version));
            ps.executeUpdate();
        }
    }

    private static void applyMigration(Connection connection, Dialect dialect, int version) throws SQLException {
        for (String statement : migrationStatements(dialect, version)) {
            try (Statement st = connection.createStatement()) {
                st.execute(statement);
            }
        }
    }

    /** 按版本返回迁移语句。v1 初始建表；v2 登录历史；v3 绑定历史（解绑/注销后可追溯 + 冷却）。 */
    private static List<String> migrationStatements(Dialect dialect, int version) {
        return switch (version) {
            case 1 -> initialStatements(dialect);
            case 2 -> loginHistoryStatements(dialect);
            case 3 -> bindingHistoryStatements(dialect);
            default -> throw new StorageException("缺少 v" + version + " 的迁移脚本");
        };
    }

    private static List<String> initialStatements(Dialect dialect) {
        List<String> out = new ArrayList<>();
        if (dialect == Dialect.MYSQL) {
            out.add("CREATE TABLE IF NOT EXISTS accounts ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " name VARCHAR(64) NOT NULL,"
                    + " name_lower VARCHAR(64) NOT NULL UNIQUE,"
                    + " uuid CHAR(36) NOT NULL UNIQUE,"
                    + " password_hash VARCHAR(255) NOT NULL,"
                    + " status VARCHAR(16) NOT NULL DEFAULT 'active',"
                    + " premium_uuid CHAR(36) NULL,"
                    + " last_server VARCHAR(64) NULL,"
                    + " created_at BIGINT NOT NULL,"
                    + " last_login_at BIGINT NULL,"
                    + " last_ip VARCHAR(45) NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            out.add("CREATE TABLE IF NOT EXISTS identities ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id BIGINT NOT NULL,"
                    + " kind VARCHAR(16) NOT NULL,"
                    + " identifier VARCHAR(128) NOT NULL,"
                    + " first_seen BIGINT NOT NULL,"
                    + " last_seen BIGINT NOT NULL,"
                    + " UNIQUE KEY uq_identities_kind_identifier (kind, identifier),"
                    + " KEY idx_identities_account (account_id),"
                    + " CONSTRAINT fk_identities_account FOREIGN KEY (account_id)"
                    + "   REFERENCES accounts (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            out.add("CREATE TABLE IF NOT EXISTS bindings ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id BIGINT NOT NULL,"
                    + " type VARCHAR(16) NOT NULL,"
                    + " identifier VARCHAR(255) NOT NULL,"
                    + " verified_at BIGINT NOT NULL,"
                    + " created_at BIGINT NOT NULL,"
                    + " UNIQUE KEY uq_bindings (type, identifier, account_id),"
                    + " KEY idx_bindings_identifier (type, identifier),"
                    + " CONSTRAINT fk_bindings_account FOREIGN KEY (account_id)"
                    + "   REFERENCES accounts (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            out.add("CREATE TABLE IF NOT EXISTS login_sessions ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id BIGINT NOT NULL,"
                    + " ip VARCHAR(45) NOT NULL,"
                    + " created_at BIGINT NOT NULL,"
                    + " expires_at BIGINT NOT NULL,"
                    + " KEY idx_login_sessions_lookup (account_id, ip),"
                    + " CONSTRAINT fk_login_sessions_account FOREIGN KEY (account_id)"
                    + "   REFERENCES accounts (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            out.add("CREATE TABLE IF NOT EXISTS audit_log ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " at BIGINT NOT NULL,"
                    + " account_id BIGINT NULL,"
                    + " action VARCHAR(32) NOT NULL,"
                    + " ip VARCHAR(45) NULL,"
                    + " detail VARCHAR(512) NULL,"
                    + " KEY idx_audit_log_at (at),"
                    + " KEY idx_audit_log_account (account_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } else {
            out.add("CREATE TABLE IF NOT EXISTS accounts ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " name TEXT NOT NULL,"
                    + " name_lower TEXT NOT NULL UNIQUE,"
                    + " uuid TEXT NOT NULL UNIQUE,"
                    + " password_hash TEXT NOT NULL,"
                    + " status TEXT NOT NULL DEFAULT 'active',"
                    + " premium_uuid TEXT,"
                    + " last_server TEXT,"
                    + " created_at INTEGER NOT NULL,"
                    + " last_login_at INTEGER,"
                    + " last_ip TEXT"
                    + ")");
            out.add("CREATE TABLE IF NOT EXISTS identities ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,"
                    + " kind TEXT NOT NULL,"
                    + " identifier TEXT NOT NULL,"
                    + " first_seen INTEGER NOT NULL,"
                    + " last_seen INTEGER NOT NULL,"
                    + " UNIQUE (kind, identifier)"
                    + ")");
            out.add("CREATE INDEX IF NOT EXISTS idx_identities_account ON identities (account_id)");
            out.add("CREATE TABLE IF NOT EXISTS bindings ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,"
                    + " type TEXT NOT NULL,"
                    + " identifier TEXT NOT NULL,"
                    + " verified_at INTEGER NOT NULL,"
                    + " created_at INTEGER NOT NULL,"
                    + " UNIQUE (type, identifier, account_id)"
                    + ")");
            out.add("CREATE INDEX IF NOT EXISTS idx_bindings_identifier ON bindings (type, identifier)");
            out.add("CREATE TABLE IF NOT EXISTS login_sessions ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,"
                    + " ip TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL,"
                    + " expires_at INTEGER NOT NULL"
                    + ")");
            out.add("CREATE INDEX IF NOT EXISTS idx_login_sessions_lookup ON login_sessions (account_id, ip)");
            out.add("CREATE TABLE IF NOT EXISTS audit_log ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " at INTEGER NOT NULL,"
                    + " account_id INTEGER,"
                    + " action TEXT NOT NULL,"
                    + " ip TEXT,"
                    + " detail TEXT"
                    + ")");
            out.add("CREATE INDEX IF NOT EXISTS idx_audit_log_at ON audit_log (at)");
            out.add("CREATE INDEX IF NOT EXISTS idx_audit_log_account ON audit_log (account_id)");
        }
        return out;
    }

    /** v2：登录历史（成功 / 失败 / 登出），供 bot 侧管理查询与异常登录排查。 */
    private static List<String> loginHistoryStatements(Dialect dialect) {
        List<String> out = new ArrayList<>();
        if (dialect == Dialect.MYSQL) {
            out.add("CREATE TABLE IF NOT EXISTS login_history ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id BIGINT NULL,"
                    + " at BIGINT NOT NULL,"
                    + " ip VARCHAR(45) NULL,"
                    + " result VARCHAR(16) NOT NULL,"
                    + " identity VARCHAR(128) NULL,"
                    + " detail VARCHAR(255) NULL,"
                    + " KEY idx_login_history_account (account_id, at),"
                    + " KEY idx_login_history_ip (ip),"
                    + " CONSTRAINT fk_login_history_account FOREIGN KEY (account_id)"
                    + "   REFERENCES accounts (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } else {
            out.add("CREATE TABLE IF NOT EXISTS login_history ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " account_id INTEGER REFERENCES accounts(id) ON DELETE CASCADE,"
                    + " at INTEGER NOT NULL,"
                    + " ip TEXT,"
                    + " result TEXT NOT NULL,"
                    + " identity TEXT,"
                    + " detail TEXT"
                    + ")");
            out.add("CREATE INDEX IF NOT EXISTS idx_login_history_account ON login_history (account_id, at)");
            out.add("CREATE INDEX IF NOT EXISTS idx_login_history_ip ON login_history (ip)");
        }
        return out;
    }

    /**
     * v3：绑定历史（bind / unbind / delete）。值不随绑定或账号删除而丢失，
     * 既用于「解绑/注销后追溯 QQ/邮箱」，也用于解绑/注销后的再绑定冷却。
     * <p>
     * {@code account_id} 故意**不设外键级联**：账号被删后记录仍保留。
     */
    private static List<String> bindingHistoryStatements(Dialect dialect) {
        List<String> out = new ArrayList<>();
        if (dialect == Dialect.MYSQL) {
            out.add("CREATE TABLE IF NOT EXISTS binding_history ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " account_id BIGINT NULL,"
                    + " type VARCHAR(16) NOT NULL,"
                    + " identifier VARCHAR(255) NOT NULL,"
                    + " action VARCHAR(16) NOT NULL,"
                    + " at BIGINT NOT NULL,"
                    + " ip VARCHAR(45) NULL,"
                    + " KEY idx_binding_history_identifier (type, identifier, at),"
                    + " KEY idx_binding_history_account (account_id, at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } else {
            out.add("CREATE TABLE IF NOT EXISTS binding_history ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " account_id INTEGER,"
                    + " type TEXT NOT NULL,"
                    + " identifier TEXT NOT NULL,"
                    + " action TEXT NOT NULL,"
                    + " at INTEGER NOT NULL,"
                    + " ip TEXT"
                    + ")");
            out.add("CREATE INDEX IF NOT EXISTS idx_binding_history_identifier"
                    + " ON binding_history (type, identifier, at)");
            out.add("CREATE INDEX IF NOT EXISTS idx_binding_history_account"
                    + " ON binding_history (account_id, at)");
        }
        return out;
    }
}
