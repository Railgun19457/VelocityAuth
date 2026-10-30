package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code accounts} 表访问。
 */
public final class AccountRepository {

    private static final String COLUMNS =
            "id, name, name_lower, uuid, password_hash, status, premium_uuid, last_server,"
                    + " created_at, last_login_at, last_ip";

    private final Database database;

    public AccountRepository(Database database) {
        this.database = database;
    }

    public Optional<Account> findById(long id) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Account>empty();
                }
            }
        });
    }

    public Optional<Account> findByNameLower(String nameLower) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE name_lower = ?")) {
                ps.setString(1, nameLower);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Account>empty();
                }
            }
        });
    }

    public Optional<Account> findByUuid(UUID uuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Account>empty();
                }
            }
        });
    }

    public boolean existsByNameLower(String nameLower) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM accounts WHERE name_lower = ?")) {
                ps.setString(1, nameLower);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public List<Account> findAll() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts ORDER BY name_lower")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public List<Account> findByNamePrefix(String prefixLower, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE name_lower LIKE ? ORDER BY name_lower LIMIT ?")) {
                ps.setString(1, prefixLower + "%");
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public long insert(String name, String nameLower, UUID uuid, String passwordHash,
                       AccountStatus status, UUID premiumUuid, long createdAt) {
        return database.inTransaction(connection -> insert(connection, name, nameLower, uuid, passwordHash,
                status, premiumUuid, createdAt));
    }

    /**
     * 在调用方提供的事务连接内插入账号（供注册流程把账号 + 身份 + 绑定写入同一事务）。
     * 不在此方法内提交/回滚。
     */
    long insert(Connection connection, String name, String nameLower, UUID uuid, String passwordHash,
                AccountStatus status, UUID premiumUuid, long createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (name, name_lower, uuid, password_hash, status, premium_uuid,"
                        + " last_server, created_at, last_login_at, last_ip)"
                        + " VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, nameLower);
            ps.setString(3, uuid.toString());
            ps.setString(4, passwordHash);
            ps.setString(5, status.dbValue());
            if (premiumUuid == null) {
                ps.setNull(6, java.sql.Types.VARCHAR);
            } else {
                ps.setString(6, premiumUuid.toString());
            }
            ps.setLong(7, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new StorageException("插入账号后未返回主键");
    }

    public void updatePasswordHash(long id, String passwordHash) {
        update("UPDATE accounts SET password_hash = ? WHERE id = ?", ps -> {
            ps.setString(1, passwordHash);
            ps.setLong(2, id);
        });
    }

    public void updateStatus(long id, AccountStatus status) {
        update("UPDATE accounts SET status = ? WHERE id = ?", ps -> {
            ps.setString(1, status.dbValue());
            ps.setLong(2, id);
        });
    }

    public void updateLastServer(long id, String server) {
        update("UPDATE accounts SET last_server = ? WHERE id = ?", ps -> {
            if (server == null) {
                ps.setNull(1, java.sql.Types.VARCHAR);
            } else {
                ps.setString(1, server);
            }
            ps.setLong(2, id);
        });
    }

    public void updateLastLogin(long id, long at, String ip) {
        update("UPDATE accounts SET last_login_at = ?, last_ip = ? WHERE id = ?", ps -> {
            ps.setLong(1, at);
            if (ip == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, ip);
            }
            ps.setLong(3, id);
        });
    }

    public void updatePremiumUuid(long id, UUID premiumUuid) {
        update("UPDATE accounts SET premium_uuid = ? WHERE id = ?", ps -> {
            if (premiumUuid == null) {
                ps.setNull(1, java.sql.Types.VARCHAR);
            } else {
                ps.setString(1, premiumUuid.toString());
            }
            ps.setLong(2, id);
        });
    }

    public int delete(long id) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    /** 仍处于 {@code pending_binding} 且注册时间早于 {@code cutoff}（unix 秒）的账号。 */
    public List<Account> findPendingOlderThan(long cutoff) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE status = ? AND created_at < ?")) {
                ps.setString(1, AccountStatus.PENDING_BINDING.dbValue());
                ps.setLong(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM accounts");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private void update(String sql, StatementBinder binder) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                binder.bind(ps);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Account map(ResultSet rs) throws SQLException {
        String premium = rs.getString("premium_uuid");
        long lastLoginRaw = rs.getLong("last_login_at");
        Long lastLogin = rs.wasNull() ? null : lastLoginRaw;
        return new Account(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("name_lower"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("password_hash"),
                AccountStatus.fromDb(rs.getString("status")),
                premium == null || premium.isEmpty() ? null : UUID.fromString(premium),
                rs.getString("last_server"),
                rs.getLong("created_at"),
                lastLogin,
                rs.getString("last_ip"));
    }

    @FunctionalInterface
    interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
