package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.Database.Dialect;
import io.github.railgun19457.velocityauth.storage.model.Identity;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code identities} 表访问：连接名 / 基岩身份 → 账号。
 */
public final class IdentityRepository {

    private final Database database;

    public IdentityRepository(Database database) {
        this.database = database;
    }

    public Optional<Identity> find(IdentityKind kind, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, account_id, kind, identifier, first_seen, last_seen"
                            + " FROM identities WHERE kind = ? AND identifier = ?")) {
                ps.setString(1, kind.dbValue());
                ps.setString(2, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Identity>empty();
                }
            }
        });
    }

    public List<Identity> findByAccountId(long accountId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, account_id, kind, identifier, first_seen, last_seen"
                            + " FROM identities WHERE account_id = ? ORDER BY last_seen DESC")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Identity> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public int countByAccountId(long accountId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM identities WHERE account_id = ?")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 记录一次身份使用；已存在则更新 account_id 与 last_seen。 */
    public void upsert(long accountId, IdentityKind kind, String identifier, long now) {
        String sql = database.dialect() == Dialect.MYSQL
                ? "INSERT INTO identities (account_id, kind, identifier, first_seen, last_seen)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE account_id = VALUES(account_id), last_seen = VALUES(last_seen)"
                : "INSERT INTO identities (account_id, kind, identifier, first_seen, last_seen)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT(kind, identifier) DO UPDATE SET"
                        + " account_id = excluded.account_id, last_seen = excluded.last_seen";
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, accountId);
                ps.setString(2, kind.dbValue());
                ps.setString(3, identifier);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int delete(IdentityKind kind, String identifier) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM identities WHERE kind = ? AND identifier = ?")) {
                ps.setString(1, kind.dbValue());
                ps.setString(2, identifier);
                return ps.executeUpdate();
            }
        });
    }

    public int deleteByAccountId(long accountId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM identities WHERE account_id = ?")) {
                ps.setLong(1, accountId);
                return ps.executeUpdate();
            }
        });
    }

    private static Identity map(ResultSet rs) throws java.sql.SQLException {
        return new Identity(
                rs.getLong("id"),
                rs.getLong("account_id"),
                IdentityKind.fromDb(rs.getString("kind")),
                rs.getString("identifier"),
                rs.getLong("first_seen"),
                rs.getLong("last_seen"));
    }
}
