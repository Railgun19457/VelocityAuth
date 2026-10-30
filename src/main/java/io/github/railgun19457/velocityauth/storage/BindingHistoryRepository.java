package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.BindingHistory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code binding_history} 表访问：绑定 / 解绑 / 注销事件，用于追溯与冷却。
 */
public final class BindingHistoryRepository {

    private static final String COLUMNS = "id, account_id, type, identifier, action, at, ip";

    private final Database database;

    public BindingHistoryRepository(Database database) {
        this.database = database;
    }

    public void insert(Long accountId, String type, String identifier, String action, long at, String ip) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO binding_history (account_id, type, identifier, action, at, ip)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                if (accountId == null) {
                    ps.setNull(1, Types.BIGINT);
                } else {
                    ps.setLong(1, accountId);
                }
                ps.setString(2, type);
                ps.setString(3, truncate(identifier, 255));
                ps.setString(4, action);
                ps.setLong(5, at);
                if (ip == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, ip);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 某个标识的全部事件，按时间倒序。 */
    public List<BindingHistory> findByIdentifier(String type, String identifier, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM binding_history WHERE type = ? AND identifier = ?"
                            + " ORDER BY at DESC, id DESC LIMIT ?")) {
                ps.setString(1, type);
                ps.setString(2, identifier);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<BindingHistory> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 某个账号的绑定历史，按时间倒序。 */
    public List<BindingHistory> findByAccountId(long accountId, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM binding_history WHERE account_id = ?"
                            + " ORDER BY at DESC, id DESC LIMIT ?")) {
                ps.setLong(1, accountId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<BindingHistory> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 该标识最近一次被释放（解绑 / 注销）的时间；从未释放返回 0。用于冷却判定。 */
    public long lastReleaseAt(String type, String identifier) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT MAX(at) FROM binding_history WHERE type = ? AND identifier = ?"
                            + " AND action IN (?, ?)")) {
                ps.setString(1, type);
                ps.setString(2, identifier);
                ps.setString(3, BindingHistory.UNBIND);
                ps.setString(4, BindingHistory.DELETE);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long value = rs.getLong(1);
                        return rs.wasNull() ? 0L : value;
                    }
                    return 0L;
                }
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM binding_history");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** 删除 {@code at < cutoff} 的绑定历史；返回删除行数。 */
    public int prune(long cutoff) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM binding_history WHERE at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static BindingHistory map(ResultSet rs) throws SQLException {
        long accountRaw = rs.getLong("account_id");
        Long accountId = rs.wasNull() ? null : accountRaw;
        return new BindingHistory(
                rs.getLong("id"),
                accountId,
                rs.getString("type"),
                rs.getString("identifier"),
                rs.getString("action"),
                rs.getLong("at"),
                rs.getString("ip"));
    }
}
