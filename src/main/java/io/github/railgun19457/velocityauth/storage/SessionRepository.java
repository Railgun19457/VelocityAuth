package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.LoginSession;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code login_sessions} 表访问：会话保持。
 */
public final class SessionRepository {

    private final Database database;

    public SessionRepository(Database database) {
        this.database = database;
    }

    public void insert(long accountId, String ip, long createdAt, long expiresAt) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO login_sessions (account_id, ip, created_at, expires_at) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, accountId);
                ps.setString(2, ip);
                ps.setLong(3, createdAt);
                ps.setLong(4, expiresAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 返回该账号 + IP 下最近一条未过期会话。 */
    public Optional<LoginSession> findValid(long accountId, String ip, long now) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, account_id, ip, created_at, expires_at FROM login_sessions"
                            + " WHERE account_id = ? AND ip = ? AND expires_at > ?"
                            + " ORDER BY expires_at DESC")) {
                ps.setLong(1, accountId);
                ps.setString(2, ip);
                ps.setLong(3, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<LoginSession>empty();
                }
            }
        });
    }

    /** 仅按 IP 查找（bind-to-ip=false 时使用）。 */
    public List<LoginSession> findAllValidByIp(String ip, long now) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, account_id, ip, created_at, expires_at FROM login_sessions"
                            + " WHERE ip = ? AND expires_at > ? ORDER BY expires_at DESC")) {
                ps.setString(1, ip);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    List<LoginSession> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public int deleteExpired(long now) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM login_sessions WHERE expires_at <= ?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            }
        });
    }

    public int deleteByAccountId(long accountId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM login_sessions WHERE account_id = ?")) {
                ps.setLong(1, accountId);
                return ps.executeUpdate();
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM login_sessions");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private static LoginSession map(ResultSet rs) throws SQLException {
        return new LoginSession(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("ip"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"));
    }
}
