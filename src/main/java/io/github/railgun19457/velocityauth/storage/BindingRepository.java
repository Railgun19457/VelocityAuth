package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.Binding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code bindings} 表访问：外部渠道（邮箱 / 社交平台账号）绑定。
 */
public final class BindingRepository {

    private static final String COLUMNS = "id, account_id, type, identifier, verified_at, created_at";

    private final Database database;

    public BindingRepository(Database database) {
        this.database = database;
    }

    public List<Binding> findByAccountId(long accountId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM bindings WHERE account_id = ? ORDER BY type")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Binding> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 全部绑定（仅用于启动预热内存索引）。 */
    public List<Binding> findAll() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS + " FROM bindings");
                 ResultSet rs = ps.executeQuery()) {
                List<Binding> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        });
    }

    public Optional<Binding> find(long accountId, String type) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM bindings WHERE account_id = ? AND type = ?")) {
                ps.setLong(1, accountId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Binding>empty();
                }
            }
        });
    }

    public List<Binding> findByIdentifier(String type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM bindings WHERE type = ? AND identifier = ?")) {
                ps.setString(1, type);
                ps.setString(2, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Binding> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 该标识已绑定的不同账号数（用于基数限制）。 */
    public int countDistinctAccounts(String type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(DISTINCT account_id) FROM bindings WHERE type = ? AND identifier = ?")) {
                ps.setString(1, type);
                ps.setString(2, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 各渠道（type）去重后的绑定账号数（type → count）。 */
    public java.util.Map<String, Integer> countAccountsByType() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT type, COUNT(DISTINCT account_id) FROM bindings GROUP BY type");
                 ResultSet rs = ps.executeQuery()) {
                java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
                return out;
            }
        });
    }

    public boolean exists(long accountId, String type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM bindings WHERE account_id = ? AND type = ? AND identifier = ?")) {
                ps.setLong(1, accountId);
                ps.setString(2, type);
                ps.setString(3, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void insert(long accountId, String type, String identifier, long verifiedAt, long createdAt) {
        database.inTransaction(connection -> {
            insert(connection, accountId, type, identifier, verifiedAt, createdAt);
            return null;
        });
    }

    /** 在调用方提供的事务连接内插入绑定（供注册流程的原子写入使用）。 */
    void insert(Connection connection, long accountId, String type, String identifier, long verifiedAt,
                long createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bindings (account_id, type, identifier, verified_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, accountId);
            ps.setString(2, type);
            ps.setString(3, identifier);
            ps.setLong(4, verifiedAt);
            ps.setLong(5, createdAt);
            ps.executeUpdate();
        }
    }

    public int delete(long accountId, String type) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM bindings WHERE account_id = ? AND type = ?")) {
                ps.setLong(1, accountId);
                ps.setString(2, type);
                return ps.executeUpdate();
            }
        });
    }

    public int deleteByAccountId(long accountId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM bindings WHERE account_id = ?")) {
                ps.setLong(1, accountId);
                return ps.executeUpdate();
            }
        });
    }

    private static Binding map(ResultSet rs) throws SQLException {
        return new Binding(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("type"),
                rs.getString("identifier"),
                rs.getLong("verified_at"),
                rs.getLong("created_at"));
    }
}
