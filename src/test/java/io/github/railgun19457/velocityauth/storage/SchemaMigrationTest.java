package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.MarkerIgnoringBase;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema 版本与 premium_uuid 列移除。
 */
@Timeout(30)
class SchemaMigrationTest {

    /** 记录 info 日志，用于断言首次安装不会打印逐版本"迁移"日志。 */
    private static final class RecordingLogger extends MarkerIgnoringBase {
        final List<String> infos = new ArrayList<>();

        @Override public String getName() { return "rec"; }
        @Override public boolean isTraceEnabled() { return false; }
        @Override public void trace(String msg) { }
        @Override public void trace(String format, Object arg) { }
        @Override public void trace(String format, Object arg1, Object arg2) { }
        @Override public void trace(String format, Object... arguments) { }
        @Override public void trace(String msg, Throwable t) { }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(String msg) { }
        @Override public void debug(String format, Object arg) { }
        @Override public void debug(String format, Object arg1, Object arg2) { }
        @Override public void debug(String format, Object... arguments) { }
        @Override public void debug(String msg, Throwable t) { }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(String msg) { infos.add(msg); }
        @Override public void info(String format, Object arg) { infos.add(format); }
        @Override public void info(String format, Object arg1, Object arg2) { infos.add(format); }
        @Override public void info(String format, Object... arguments) { infos.add(format); }
        @Override public void info(String msg, Throwable t) { infos.add(msg); }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(String msg) { }
        @Override public void warn(String format, Object arg) { }
        @Override public void warn(String format, Object... arguments) { }
        @Override public void warn(String format, Object arg1, Object arg2) { }
        @Override public void warn(String msg, Throwable t) { }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(String msg) { }
        @Override public void error(String format, Object arg) { }
        @Override public void error(String format, Object... arguments) { }
        @Override public void error(String format, Object arg1, Object arg2) { }
        @Override public void error(String msg, Throwable t) { }
    }

    private static VelocityAuthConfig.Database sqliteConfig() {
        return new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("test.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90);
    }

    @Test
    void freshInstallLogsSingleLineNotPerVersionMigrations(@TempDir Path dir) throws Exception {
        RecordingLogger logger = new RecordingLogger();
        StorageService storage = StorageService.open(sqliteConfig(), dir, logger);
        try {
            long migrationLines = logger.infos.stream().filter(m -> m.contains("已应用数据库迁移")).count();
            assertTrue(migrationLines == 0, "全新安装不应打印逐版本迁移日志：" + logger.infos);
            assertTrue(logger.infos.stream().anyMatch(m -> m.contains("已初始化数据库结构")),
                    "应有一行初始化日志：" + logger.infos);
        } finally {
            storage.close();
        }
    }

    @Test
    void premiumUuidColumnIsGone(@TempDir Path dir) throws Exception {
        StorageService storage = StorageService.open(sqliteConfig(), dir, org.slf4j.helpers.NOPLogger.NOP_LOGGER);
        try {
            List<String> cols = new ArrayList<>();
            storage.database().inTransaction(connection -> {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA table_info(accounts)")) {
                    while (rs.next()) {
                        cols.add(rs.getString("name"));
                    }
                }
                return null;
            });
            assertTrue(cols.contains("name_lower"), cols.toString());
            assertFalse(cols.contains("premium_uuid"), "premium_uuid 应已被移除：" + cols);
            // 基础读写仍然正常
            storage.accounts().insert("A", "a", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);
            assertTrue(storage.accounts().existsByNameLower("a"));
        } finally {
            storage.close();
        }
    }
}
