package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.query.AccountQueryService;
import io.github.railgun19457.velocityauth.storage.AccountRepository;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 把 bot 侧的 {@code query.*} 业务消息翻译为 {@link AccountQueryService} 调用，
 * 并组装协议载荷（PROTOCOL.md 8，扁平字段）。
 * <p>
 * 列表 / 详情类响应统一放在 {@code data} 字段（紧凑 JSON 字符串），见 {@link BotPayloads}。
 */
public final class AccountQueryHandler implements BotQueryHandler {

    /** 趋势最多回溯天数（与 login-history 默认保留期一致）。 */
    private static final int MAX_RANGE_DAYS = 90;

    private final AccountQueryService queries;
    private final StorageService storage;
    private final Supplier<VelocityAuthConfig> config;
    private final IntSupplier onlineCount;
    private final IntSupplier botConnected;
    private final Supplier<Set<Long>> onlineAccountIds;
    private final Supplier<List<OnlineSession>> onlineSessions;

    /** 在线会话视图（供管理页列表）。 */
    public record OnlineSession(long accountId, String account, String connectionName, String ip, long authenticatedAt) {
    }

    public AccountQueryHandler(AccountQueryService queries, StorageService storage,
                               Supplier<VelocityAuthConfig> config, IntSupplier onlineCount,
                               IntSupplier botConnected, Supplier<Set<Long>> onlineAccountIds,
                               Supplier<List<OnlineSession>> onlineSessions) {
        this.queries = queries;
        this.storage = storage;
        this.config = config;
        this.onlineCount = onlineCount;
        this.botConnected = botConnected;
        this.onlineAccountIds = onlineAccountIds;
        this.onlineSessions = onlineSessions;
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
            case "query.stats" -> stats(payload);
            case "query.accounts.list" -> accountsList(payload);
            case "query.sessions.list" -> sessionsList(payload);
            case "query.audit.list" -> auditList(payload);
            default -> QueryResponse.error("bad_request", "未知查询：" + type);
        };
    }

    // ------------------------------------------------------------------ query.stats

    private QueryResponse stats(Map<String, Object> payload) {
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

        // 活跃度：range 可选（1-90 天），未指定时不返回趋势，保持旧响应兼容
        int range = clamp(intOf(payload.get("range"), 0), 0, MAX_RANGE_DAYS);
        AccountQueryService.Activity activity = queries.activity(range);
        out.put("dau", activity.dau());
        out.put("wau", activity.wau());
        out.put("mau", activity.mau());
        if (range > 0) {
            List<Map<String, Object>> trend = new ArrayList<>(activity.trend().size());
            for (AccountQueryService.DailyActivity day : activity.trend()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", day.date());
                row.put("logins", day.logins());
                row.put("accounts", day.accounts());
                trend.add(row);
            }
            out.put("trend", BotPayloads.encodeArray(trend));
        }
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
        out.put("online", onlineAccountIds.get().contains(view.id()));

        // 详情用 data 补充身份时间与绑定明细，便于管理页直接渲染
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", view.id());
        detail.put("name", view.name());
        detail.put("uuid", view.uuid().toString());
        detail.put("status", view.status());
        detail.put("last_server", orEmpty(view.lastServer()));
        detail.put("created_at", view.createdAt());
        detail.put("last_login_at", orZero(view.lastLoginAt()));
        detail.put("last_ip", orEmpty(view.lastIp()));
        detail.put("online", onlineAccountIds.get().contains(view.id()));
        List<Map<String, Object>> bindingRows = new ArrayList<>(view.bindings().size());
        for (Binding binding : view.bindings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", binding.type());
            row.put("identifier", binding.identifier());
            row.put("verified_at", binding.verifiedAt());
            bindingRows.add(row);
        }
        detail.put("bindings", bindingRows);
        List<Map<String, Object>> identityRows = new ArrayList<>(view.identities().size());
        for (AccountQueryService.IdentityView identity : view.identities()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", identity.kind());
            row.put("identifier", identity.identifier());
            row.put("first_seen", identity.firstSeen());
            row.put("last_seen", identity.lastSeen());
            identityRows.add(row);
        }
        detail.put("identities", identityRows);
        out.put("data", BotPayloads.encodeObject(detail));
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

        // data：带失败原因等明细，供管理页展示
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (LoginHistory record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", record.at());
            row.put("result", record.result());
            row.put("ip", orEmpty(record.ip()));
            row.put("identity", orEmpty(record.identity()));
            row.put("detail", orEmpty(record.detail()));
            rows.add(row);
        }
        out.put("data", BotPayloads.encodeArray(rows));
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

    // ------------------------------------------------------------------ query.accounts.list

    private QueryResponse accountsList(Map<String, Object> payload) {
        int maxPageSize = Math.max(1, config.get().bot().admin().maxPageSize());
        int page = Math.max(1, intOf(payload.get("page"), 1));
        int pageSize = clamp(intOf(payload.get("limit"), 20), 1, maxPageSize);

        String status = lower(payload.get("status"));
        if (status != null && !status.equals("active") && !status.equals("locked")
                && !status.equals("pending_binding")) {
            return QueryResponse.error("bad_request", "status 仅支持 active | locked | pending_binding");
        }
        String provider = lower(payload.get("provider"));
        if (provider != null && !Provider.exists(config.get(), provider)) {
            return QueryResponse.error("bad_request", "provider 必须是已配置的渠道 id");
        }

        AccountRepository.Search spec = new AccountRepository.Search(
                str(payload.get("keyword")),
                status,
                provider,
                str(payload.get("identifier")),
                nullableLong(payload.get("created_after")),
                nullableLong(payload.get("created_before")),
                nullableLong(payload.get("last_login_after")),
                nullableLong(payload.get("last_login_before")),
                boolOf(payload.get("online")),
                List.copyOf(onlineAccountIds.get()),
                str(payload.get("sort")),
                (page - 1) * pageSize,
                pageSize);

        AccountQueryService.Page<AccountQueryService.AccountSummary> result =
                queries.search(spec, page, pageSize, onlineAccountIds.get());

        List<Map<String, Object>> rows = new ArrayList<>(result.items().size());
        for (AccountQueryService.AccountSummary account : result.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", account.id());
            row.put("name", account.name());
            row.put("uuid", account.uuid().toString());
            row.put("status", account.status());
            row.put("last_server", orEmpty(account.lastServer()));
            row.put("created_at", account.createdAt());
            row.put("last_login_at", orZero(account.lastLoginAt()));
            row.put("last_ip", orEmpty(account.lastIp()));
            row.put("bindings", account.bindings());
            row.put("online", account.online());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", result.total() > 0);
        out.put("total", result.total());
        out.put("page", result.page());
        out.put("page_size", result.pageSize());
        out.put("count", rows.size());
        out.put("data", BotPayloads.encodeArray(rows));
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ query.sessions.list

    private QueryResponse sessionsList(Map<String, Object> payload) {
        int maxPageSize = Math.max(1, config.get().bot().admin().maxPageSize());
        int page = Math.max(1, intOf(payload.get("page"), 1));
        int pageSize = clamp(intOf(payload.get("limit"), 20), 1, maxPageSize);

        List<OnlineSession> all = onlineSessions.get();
        int total = all.size();
        int from = Math.min((page - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> rows = new ArrayList<>(Math.max(0, to - from));
        for (OnlineSession session : all.subList(from, to)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("account_id", session.accountId());
            row.put("account", orEmpty(session.account()));
            row.put("connection", orEmpty(session.connectionName()));
            row.put("ip", orEmpty(session.ip()));
            row.put("authenticated_at", session.authenticatedAt());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", total > 0);
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("count", rows.size());
        out.put("data", BotPayloads.encodeArray(rows));
        return QueryResponse.ok(out);
    }

    // ------------------------------------------------------------------ query.audit.list

    private QueryResponse auditList(Map<String, Object> payload) {
        int maxPageSize = Math.max(1, config.get().bot().admin().maxPageSize());
        int page = Math.max(1, intOf(payload.get("page"), 1));
        int pageSize = clamp(intOf(payload.get("limit"), 20), 1, maxPageSize);

        Long accountId = nullableLong(payload.get("account_id"));
        String action = str(payload.get("action"));
        String actor = str(payload.get("actor"));
        String source = lower(payload.get("source"));
        if (source != null && !source.equals(AuditEntry.SOURCE_BOT) && !source.equals(AuditEntry.SOURCE_COMMAND)
                && !source.equals(AuditEntry.SOURCE_PLUGIN)) {
            return QueryResponse.error("bad_request", "source 仅支持 bot | command | plugin");
        }
        long since = longOf(payload.get("since"), 0L);
        long until = longOf(payload.get("until"), 0L);

        int total = storage.audit().countSearch(accountId, action, actor, source, since, until);
        List<AuditEntry> entries = total == 0 ? List.of()
                : storage.audit().search(accountId, action, actor, source, since, until,
                (page - 1) * pageSize, pageSize);

        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (AuditEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("at", entry.at());
            row.put("account_id", entry.accountId() == null ? 0L : entry.accountId());
            row.put("account", entry.accountId() == null ? ""
                    : queries.accountName(entry.accountId()).orElse(""));
            row.put("action", entry.action());
            row.put("ip", orEmpty(entry.ip()));
            row.put("detail", orEmpty(entry.detail()));
            row.put("actor", orEmpty(entry.actor()));
            row.put("source", orEmpty(entry.source()));
            row.put("client_id", orEmpty(entry.clientId()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", total > 0);
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("count", rows.size());
        out.put("data", BotPayloads.encodeArray(rows));
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

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = str(value);
        if (text == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(text.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean boolOf(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
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

    /** 便于命令层复用：账号名列表 → 逗号串。 */
    static String join(List<String> values) {
        return String.join(",", values);
    }

    /** 供测试 / 命令层构造摘要行。 */
    static Map<String, Object> accountRow(Account account) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", account.id());
        row.put("name", account.name());
        row.put("uuid", account.uuid().toString());
        row.put("status", account.status().dbValue());
        return row;
    }
}
