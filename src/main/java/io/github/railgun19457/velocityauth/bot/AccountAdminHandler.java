package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.admin.AdminService;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.AuditContext;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code admin.*} 写操作的默认实现（PROTOCOL.md 8.2）。
 * <p>
 * 只负责：参数校验 → 调 {@link AdminService} → 组装响应。
 * 「谁可以发」由 {@link BotServer} 在进入这里之前判定（已配对 + 已就绪 + 客户端白名单）；
 * 所有写操作都带审计（{@link AuditContext#bot}），并可通过 {@code bot.admin.read-only} 整体关闭。
 */
public final class AccountAdminHandler implements BotAdminHandler {

    private final AdminService admin;
    private final Supplier<VelocityAuthConfig> config;
    private final PluginLogger log;

    public AccountAdminHandler(AdminService admin, Supplier<VelocityAuthConfig> config, PluginLogger log) {
        this.admin = admin;
        this.config = config;
        this.log = log;
    }

    @Override
    public QueryResponse handle(String type, Map<String, Object> payload, String clientId, String remoteIp) {
        if (config.get().bot().admin().readOnly()) {
            return QueryResponse.error("admin_read_only", "管理接口处于只读模式");
        }
        AuditContext audit = AuditContext.bot(actorOf(payload, clientId), clientId);
        return switch (type) {
            case "admin.account.set-status" -> setStatus(payload, remoteIp, audit);
            case "admin.account.reset-password" -> resetPassword(payload, remoteIp, audit);
            case "admin.account.delete" -> delete(payload, remoteIp, audit);
            case "admin.account.kick" -> kick(payload, remoteIp, audit);
            case "admin.account.clear-session" -> clearSession(payload, remoteIp, audit);
            case "admin.binding.remove" -> removeBinding(payload, remoteIp, audit);
            default -> QueryResponse.error("bad_request", "未知管理操作：" + type);
        };
    }

    // ------------------------------------------------------------------ 账号状态

    private QueryResponse setStatus(Map<String, Object> payload, String ip, AuditContext audit) {
        Optional<Account> found = resolve(payload);
        if (found.isEmpty()) {
            return QueryResponse.error("bad_request", "账号不存在或缺少 id/name");
        }
        String raw = lower(payload.get("status"));
        AccountStatus status = switch (raw == null ? "" : raw) {
            case "active" -> AccountStatus.ACTIVE;
            case "locked" -> AccountStatus.LOCKED;
            default -> null;
        };
        if (status == null) {
            return QueryResponse.error("bad_request", "status 仅支持 active | locked");
        }
        Account account = found.get();
        if (!admin.setStatus(account, status, ip, audit)) {
            return QueryResponse.error("bad_request", "更新账号状态失败");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", account.id());
        out.put("name", account.name());
        out.put("status", status.dbValue());
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ 重置密码

    private QueryResponse resetPassword(Map<String, Object> payload, String ip, AuditContext audit) {
        Optional<Account> found = resolve(payload);
        if (found.isEmpty()) {
            return QueryResponse.error("bad_request", "账号不存在或缺少 id/name");
        }
        String password = str(payload.get("password"));
        if (password == null || password.isEmpty()) {
            return QueryResponse.error("bad_request", "缺少 password");
        }
        AuthOutcomes.ChangePassword result = admin.resetPassword(found.get(), password, ip, audit);
        return switch (result.status()) {
            case SUCCESS -> ok(found.get());
            case PASSWORD_TOO_SHORT -> QueryResponse.error("password_too_short", "密码太短");
            case PASSWORD_TOO_LONG -> QueryResponse.error("password_too_long", "密码太长");
            case NOT_FOUND -> QueryResponse.error("bad_request", "账号不存在");
            default -> QueryResponse.error("internal_error", "重置密码失败");
        };
    }

    // ------------------------------------------------------------------ 删除账号

    private QueryResponse delete(Map<String, Object> payload, String ip, AuditContext audit) {
        Optional<Account> found = resolve(payload);
        if (found.isEmpty()) {
            return QueryResponse.error("bad_request", "账号不存在或缺少 id/name");
        }
        Account account = found.get();
        String confirm = str(payload.get("confirm_name"));
        if (confirm == null || !confirm.trim().equalsIgnoreCase(account.name())) {
            return QueryResponse.error("confirm_required", "需在 confirm_name 中回填账号名以确认删除");
        }
        if (!admin.delete(account, ip, audit)) {
            return QueryResponse.error("bad_request", "删除账号失败");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", account.id());
        out.put("name", account.name());
        out.put("deleted", true);
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ 踢下线 / 清会话

    private QueryResponse kick(Map<String, Object> payload, String ip, AuditContext audit) {
        Optional<Account> found = resolve(payload);
        if (found.isEmpty()) {
            return QueryResponse.error("bad_request", "账号不存在或缺少 id/name");
        }
        Account account = found.get();
        String reason = str(payload.get("reason"));
        int planned = admin.kick(account, reason == null || reason.isBlank() ? "管理员已将会话踢出" : reason,
                ip, audit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", account.id());
        out.put("name", account.name());
        out.put("sessions", planned);
        return QueryResponse.ok(out);
    }

    private QueryResponse clearSession(Map<String, Object> payload, String ip, AuditContext audit) {
        Optional<Account> found = resolve(payload);
        if (found.isEmpty()) {
            return QueryResponse.error("bad_request", "账号不存在或缺少 id/name");
        }
        Account account = found.get();
        admin.clearSessions(account, ip, audit);
        return ok(account);
    }

    // ------------------------------------------------------------------ 解绑

    private QueryResponse removeBinding(Map<String, Object> payload, String ip, AuditContext audit) {
        Optional<Account> found = resolve(payload);
        if (found.isEmpty()) {
            return QueryResponse.error("bad_request", "账号不存在或缺少 id/name");
        }
        Account account = found.get();
        String provider = lower(payload.get("provider"));
        if (provider == null || provider.isBlank()) {
            return QueryResponse.error("bad_request", "缺少 provider");
        }
        if (!admin.canUnbind(account, provider)) {
            return QueryResponse.error("binding_required",
                    "该渠道是账号唯一的必需绑定，解绑后账号将无法登录");
        }
        AuthOutcomes.Bind result = admin.unbind(account, provider, ip, audit);
        return switch (result.status()) {
            case SUCCESS -> ok(account);
            case NOT_BOUND -> QueryResponse.error("not_bound", "该渠道未绑定");
            case DISABLED -> QueryResponse.error("bad_request", "渠道不存在或未启用");
            default -> QueryResponse.error("internal_error", "解绑失败");
        };
    }

    // ------------------------------------------------------------------ 工具

    private Optional<Account> resolve(Map<String, Object> payload) {
        Long id = longOrNull(payload.get("id"));
        if (id != null) {
            Optional<Account> byId = admin.findById(id);
            if (byId.isPresent()) {
                return byId;
            }
        }
        return admin.findByName(str(payload.get("name")));
    }

    private static String actorOf(Map<String, Object> payload, String clientId) {
        String actor = str(payload.get("actor"));
        return actor == null || actor.isBlank() ? clientId : actor.trim();
    }

    private static QueryResponse ok(Account account) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", account.id());
        out.put("name", account.name());
        return QueryResponse.ok(out);
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static String lower(Object value) {
        String text = str(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = str(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
