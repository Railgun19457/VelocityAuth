package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code bindings} 表访问：邮箱 / QQ 绑定。
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

    public Optional<Binding> find(long accountId, BindingType type) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM bindings WHERE account_id = ? AND type = ?")) {
                ps.setLong(1, accountId);
                ps.setString(2, type.dbValue());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Binding>empty();
                }
            }
        });
    }

    public List<Binding> findByIdentifier(BindingType type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM bindings WHERE type = ? AND identifier = ?")) {
                ps.setString(1, type.dbValue());
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
    public int countDistinctAccounts(BindingType type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(DISTINCT account_id) FROM bindings WHERE type = ? AND identifier = ?")) {
                ps.setString(1, type.dbValue());
                ps.setString(2, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public boolean exists(long accountId, BindingType type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM bindings WHERE account_id = ? AND type = ? AND identifier = ?")) {
                ps.setLong(1, accountId);
                ps.setString(2, type.dbValue());
                ps.setString(3, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void insert(long accountId, BindingType type, String identifier, long verifiedAt, long createdAt) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO bindings (account_id, type, identifier, verified_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, accountId);
                ps.setString(2, type.dbValue());
                ps.setString(3, identifier);
                ps.setLong(4, verifiedAt);
                ps.setLong(5, createdAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int delete(long accountId, BindingType type) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM bindings WHERE account_id = ? AND type = ?")) {
                ps.setLong(1, accountId);
                ps.setString(2, type.dbValue());
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
                BindingType.fromDb(rs.getString("type")),
                rs.getString("identifier"),
                rs.getLong("verified_at"),
                rs.getLong("created_at"));
    }
}
