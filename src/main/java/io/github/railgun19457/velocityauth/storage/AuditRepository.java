package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.AuditEntry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code audit_log} 表访问。
 */
public final class AuditRepository {

    private final Database database;

    public AuditRepository(Database database) {
        this.database = database;
    }

    public void log(long at, Long accountId, String action, String ip, String detail) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO audit_log (at, account_id, action, ip, detail) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, at);
                if (accountId == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, accountId);
                }
                ps.setString(3, action);
                if (ip == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, ip);
                }
                if (detail == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, truncate(detail));
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<AuditEntry> recent(int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, at, account_id, action, ip, detail FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<AuditEntry> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public List<AuditEntry> findByAccountId(long accountId, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, at, account_id, action, ip, detail FROM audit_log"
                            + " WHERE account_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, accountId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<AuditEntry> out = new ArrayList<>();
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
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM audit_log");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** 删除 {@code at < cutoff} 的审计记录；返回删除行数。 */
    public int prune(long cutoff) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM audit_log WHERE at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static AuditEntry map(ResultSet rs) throws SQLException {
        long accountRaw = rs.getLong("account_id");
        Long accountId = rs.wasNull() ? null : accountRaw;
        return new AuditEntry(
                rs.getLong("id"),
                rs.getLong("at"),
                accountId,
                rs.getString("action"),
                rs.getString("ip"),
                rs.getString("detail"));
    }
}
