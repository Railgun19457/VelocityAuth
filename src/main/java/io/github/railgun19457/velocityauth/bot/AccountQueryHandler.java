package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.query.AccountQueryService;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 把 bot 侧的 {@code query.*} 业务消息翻译为 {@link AccountQueryService} 调用，
 * 并组装协议载荷（PROTOCOL.md 8，扁平字段）。
 */
public final class AccountQueryHandler implements BotQueryHandler {

    private final AccountQueryService queries;
    private final Supplier<VelocityAuthConfig> config;
    private final IntSupplier onlineCount;
    private final IntSupplier botConnected;

    public AccountQueryHandler(AccountQueryService queries, Supplier<VelocityAuthConfig> config,
                               IntSupplier onlineCount, IntSupplier botConnected) {
        this.queries = queries;
        this.config = config;
        this.onlineCount = onlineCount;
        this.botConnected = botConnected;
    }

    @Override
    public QueryResponse handle(String type, Map<String, Object> payload) {
        int max = config.get().bot().query().maxResults();
        int limit = clamp(intOf(payload.get("limit"), max), 1, max);
        return switch (type) {
            case "query.account" -> account(payload);
            case "query.lookup" -> lookup(payload, limit);
            case "query.history" -> history(payload, limit);
            case "query.binding-history" -> bindingHistory(payload, limit);
            case "query.stats" -> stats();
            default -> QueryResponse.error("bad_request", "未知查询：" + type);
        };
    }

    // ------------------------------------------------------------------ query.stats

    private QueryResponse stats() {
        AccountQueryService.Stats stats = queries.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accounts_total", stats.accountsTotal());
        for (Map.Entry<String, Integer> entry : stats.accountsByStatus().entrySet()) {
            out.put("accounts_" + entry.getKey(), entry.getValue());
        }
        // 先按已配置渠道补 0，再用实际值覆盖，保证每个渠道都有字段
        for (Provider provider : Provider.all(config.get())) {
            out.putIfAbsent("bound_" + provider.id(), 0);
        }
        for (Map.Entry<String, Integer> entry : stats.accountsByProvider().entrySet()) {
            out.put("bound_" + entry.getKey(), entry.getValue());
        }
        out.put("online", onlineCount.getAsInt());
        out.put("bot_connected", botConnected.getAsInt());
        out.put("registered_24h", stats.registered24h());
        out.put("registered_7d", stats.registered7d());
        out.put("registered_30d", stats.registered30d());
        out.put("logins_24h", stats.logins24h());
        out.put("logins_7d", stats.logins7d());
        out.put("logins_30d", stats.logins30d());
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ query.account

    private QueryResponse account(Map<String, Object> payload) {
        String by = lower(payload.get("by"));
        String value = str(payload.get("value"));
        if (by == null || value == null || value.isBlank()) {
            return QueryResponse.error("bad_request", "缺少 by/value");
        }
        Optional<AccountQueryService.AccountView> found = switch (by) {
            case "name" -> queries.byName(value);
            case "uuid" -> queries.byUuid(value);
            default -> null;
        };
        if (found == null) {
            return QueryResponse.error("bad_request", "by 仅支持 name | uuid");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (found.isEmpty()) {
            out.put("found", false);
            return QueryResponse.ok(out);
        }
        AccountQueryService.AccountView view = found.get();
        out.put("found", true);
        out.put("id", view.id());
        out.put("name", view.name());
        out.put("uuid", view.uuid().toString());
        out.put("status", view.status());
        out.put("premium_uuid", orEmpty(view.premiumUuid()));
        out.put("last_server", orEmpty(view.lastServer()));
        out.put("created_at", view.createdAt());
        out.put("last_login_at", orZero(view.lastLoginAt()));
        out.put("last_ip", orEmpty(view.lastIp()));
        out.put("bindings", encodeBindings(view.bindings()));
        List<String> identities = new ArrayList<>(view.identities().size());
        for (AccountQueryService.IdentityView identity : view.identities()) {
            identities.add(identity.kind() + ":" + identity.identifier());
        }
        out.put("identities", String.join(",", identities));
        return QueryResponse.ok(out);
    }

    /** 绑定串：`provider:identifier:verified_at`，多条以 `;` 连接。 */
    private static String encodeBindings(List<Binding> bindings) {
        List<String> out = new ArrayList<>(bindings.size());
        for (Binding binding : bindings) {
            out.add(binding.type() + ":" + binding.identifier() + ":" + binding.verifiedAt());
        }
        return String.join(";", out);
    }

    // ------------------------------------------------------------------ query.lookup

    private QueryResponse lookup(Map<String, Object> payload, int limit) {
        String by = lower(payload.get("by"));
        String value = str(payload.get("value"));
        if (by == null || value == null || value.isBlank()) {
            return QueryResponse.error("bad_request", "缺少 by/value");
        }
        List<String> accounts;
        if (by.equals("ip")) {
            accounts = queries.lookupIp(value, limit);
        } else if (Provider.exists(config.get(), by)) {
            accounts = queries.lookupBinding(by, value, limit);
        } else {
            return QueryResponse.error("bad_request", "by 仅支持 ip 或已配置的渠道 id");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", !accounts.isEmpty());
        out.put("by", by);
        out.put("value", value);
        out.put("count", accounts.size());
        out.put("accounts", String.join(",", accounts));
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ query.history

    private QueryResponse history(Map<String, Object> payload, int limit) {
        String by = lower(payload.get("by"));
        String value = str(payload.get("value"));
        if (by == null || value == null || value.isBlank()) {
            return QueryResponse.error("bad_request", "缺少 by/value");
        }
        Optional<AccountQueryService.AccountView> found = switch (by) {
            case "name" -> queries.byName(value);
            case "uuid" -> queries.byUuid(value);
            default -> null;
        };
        if (found == null) {
            return QueryResponse.error("bad_request", "by 仅支持 name | uuid");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (found.isEmpty()) {
            out.put("found", false);
            out.put("count", 0);
            out.put("entries", "");
            return QueryResponse.ok(out);
        }
        String result = lower(payload.get("result"));
        if (result != null && !result.equals(LoginHistory.SUCCESS) && !result.equals(LoginHistory.FAILED)
                && !result.equals(LoginHistory.LOGOUT)) {
            return QueryResponse.error("bad_request", "result 仅支持 success | failed | logout");
        }
        long since = longOf(payload.get("since"), 0L);
        List<LoginHistory> records = queries.history(found.get().id(), result, since, limit);
        List<String> entries = new ArrayList<>(records.size());
        for (LoginHistory record : records) {
            entries.add(record.at() + "|" + record.result() + "|" + orEmpty(record.ip())
                    + "|" + orEmpty(record.identity()));
        }
        out.put("found", true);
        out.put("count", entries.size());
        out.put("entries", String.join(";", entries));
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ query.binding-history

    private QueryResponse bindingHistory(Map<String, Object> payload, int limit) {
        String by = lower(payload.get("by"));
        String value = str(payload.get("value"));
        if (by == null || value == null || value.isBlank()) {
            return QueryResponse.error("bad_request", "缺少 by/value");
        }
        Optional<Provider> provider = Provider.find(config.get(), by);
        if (provider.isEmpty()) {
            return QueryResponse.error("bad_request", "by 必须是已配置的渠道 id");
        }
        List<BindingHistory> records = queries.bindingHistory(by, value, limit);
        List<String> entries = new ArrayList<>(records.size());
        for (BindingHistory record : records) {
            String accountId = record.accountId() == null ? "" : Long.toString(record.accountId());
            String account = record.accountId() == null ? ""
                    : queries.accountName(record.accountId()).orElse("");
            entries.add(record.at() + "|" + record.action() + "|" + orEmpty(record.ip())
                    + "|" + accountId + "|" + account);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", !records.isEmpty());
        out.put("by", by);
        out.put("value", value);
        out.put("count", entries.size());
        out.put("entries", String.join(";", entries));
        long lastRelease = queries.lastReleaseAt(by, value);
        int cooldownMinutes = provider.get().cooldownMinutes();
        long cooldownUntil = (lastRelease > 0 && cooldownMinutes > 0)
                ? lastRelease + cooldownMinutes * 60L : 0L;
        out.put("cooldown_until", cooldownUntil);
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ 工具

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

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = str(value);
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOf(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = str(value);
        if (text == null) {
            return fallback;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
