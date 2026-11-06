package io.github.railgun19457.velocityauth.query;

import io.github.railgun19457.velocityauth.auth.AccountCache;
import io.github.railgun19457.velocityauth.storage.AccountRepository;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.storage.model.Identity;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家信息查询：账号详情、按渠道标识/IP 反查账号、登录历史、绑定历史。
 * <p>
 * 同时服务于管理命令（{@code /vauth account info}）与 bot 侧查询接口，避免两套查询逻辑。
 * 只做数据读取与聚合，不做鉴权（bot 通道的权限校验由 bot 侧负责）。
 */
public final class AccountQueryService {

    private final StorageService storage;
    private final AccountCache cache;

    public AccountQueryService(StorageService storage, AccountCache cache) {
        this.storage = storage;
        this.cache = cache;
    }

    /** 账号详情视图（含绑定与身份）。 */
    public record AccountView(
            long id,
            String name,
            UUID uuid,
            String status,
            String lastServer,
            long createdAt,
            Long lastLoginAt,
            String lastIp,
            List<Binding> bindings,
            List<IdentityView> identities
    ) {
    }

    public record IdentityView(String kind, String identifier, long firstSeen, long lastSeen) {
    }

    /** 管理端账号摘要（列表行）。 */
    public record AccountSummary(
            long id,
            String name,
            UUID uuid,
            String status,
            String lastServer,
            long createdAt,
            Long lastLoginAt,
            String lastIp,
            String bindings,
            boolean online
    ) {
    }

    /** 分页结果。 */
    public record Page<T>(List<T> items, int total, int page, int pageSize) {
    }

    // ------------------------------------------------------------------ 账号详情

    public Optional<AccountView> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return cache.byNameLower(lower)
                .or(() -> storage.accounts().findByNameLower(lower))
                .map(this::view);
    }

    public Optional<AccountView> byUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return Optional.empty();
        }
        final UUID parsed;
        try {
            parsed = UUID.fromString(uuid.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return storage.accounts().findByUuid(parsed).map(this::view);
    }

    /** 由已加载的账号对象组装视图。 */
    public AccountView view(Account account) {
        List<IdentityView> identities = new ArrayList<>();
        for (Identity identity : storage.identities().findByAccountId(account.id())) {
            identities.add(new IdentityView(identity.kind().dbValue(), identity.identifier(),
                    identity.firstSeen(), identity.lastSeen()));
        }
        return new AccountView(
                account.id(),
                account.name(),
                account.uuid(),
                account.status().dbValue(),
                account.lastServer(),
                account.createdAt(),
                account.lastLoginAt(),
                account.lastIp(),
                storage.bindings().findByAccountId(account.id()),
                List.copyOf(identities));
    }

    // ------------------------------------------------------------------ 反查

    /** 按渠道标识（provider id + 值）反查账号名（去重，最多 {@code limit} 个）。 */
    public List<String> lookupBinding(String providerId, String identifier, int limit) {
        if (identifier == null || identifier.isBlank()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Binding binding : storage.bindings().findByIdentifier(providerId, identifier.trim())) {
            if (names.size() >= limit) {
                break;
            }
            resolveAccount(binding.accountId()).map(Account::name).ifPresent(names::add);
        }
        return List.copyOf(names);
    }

    /** 按登录 IP 反查账号名（登录历史 + 最近登录 IP，去重，最多 {@code limit} 个）。 */
    public List<String> lookupIp(String ip, int limit) {
        if (ip == null || ip.isBlank()) {
            return List.of();
        }
        String value = ip.trim();
        Set<Long> ids = new LinkedHashSet<>(storage.loginHistory().distinctAccountIdsByIp(value, limit));
        for (Account account : storage.accounts().findByLastIp(value, limit)) {
            if (ids.size() >= limit) {
                break;
            }
            ids.add(account.id());
        }
        Set<String> names = new LinkedHashSet<>();
        for (Long id : ids) {
            if (names.size() >= limit) {
                break;
            }
            resolveAccount(id).map(Account::name).ifPresent(names::add);
        }
        return List.copyOf(names);
    }

    // ------------------------------------------------------------------ 登录历史

    public List<LoginHistory> history(long accountId, String result, long since, int limit) {
        return storage.loginHistory().findByAccountId(accountId, result, since, limit);
    }

    // ------------------------------------------------------------------ 绑定历史

    /** 某渠道标识的绑定历史（bind / unbind / delete），按时间倒序。 */
    public List<BindingHistory> bindingHistory(String providerId, String identifier, int limit) {
        if (identifier == null || identifier.isBlank()) {
            return List.of();
        }
        return storage.bindingHistory().findByIdentifier(providerId, identifier.trim(), limit);
    }

    /** 某标识最近一次被释放（解绑 / 注销）的时间（unix 秒）；从未释放返回 0。 */
    public long lastReleaseAt(String providerId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return 0L;
        }
        return storage.bindingHistory().lastReleaseAt(providerId, identifier.trim());
    }

    // ------------------------------------------------------------------ 管理端检索

    /**
     * 分页检索账号（管理端列表）。在线状态由调用方通过 {@code onlineIds} 传入——
     * 运行时状态不在库里，服务层不反向依赖会话注册表。
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     * @param onlineIds 当前在线已认证的账号 id 集合
     */
    public Page<AccountSummary> search(AccountRepository.Search spec, int page, int pageSize,
                                       Set<Long> onlineIds) {
        int total = storage.accounts().countSearch(spec);
        List<Account> accounts = total == 0 ? List.of() : storage.accounts().search(spec);
        List<AccountSummary> rows = new ArrayList<>(accounts.size());
        for (Account account : accounts) {
            rows.add(summary(account, onlineIds));
        }
        return new Page<>(rows, total, page, pageSize);
    }

    private AccountSummary summary(Account account, Set<Long> onlineIds) {
        List<Binding> bindings = storage.bindings().findByAccountId(account.id());
        List<String> encoded = new ArrayList<>(bindings.size());
        for (Binding binding : bindings) {
            encoded.add(binding.type() + ":" + binding.identifier());
        }
        return new AccountSummary(
                account.id(),
                account.name(),
                account.uuid(),
                account.status().dbValue(),
                account.lastServer(),
                account.createdAt(),
                account.lastLoginAt(),
                account.lastIp(),
                String.join(";", encoded),
                onlineIds != null && onlineIds.contains(account.id()));
    }

    // ------------------------------------------------------------------ 统计

    /** 聚合统计（无 PII）：账号 / 绑定 / 注册与登录时间窗。 */
    public record Stats(
            int accountsTotal,
            Map<String, Integer> accountsByStatus,
            Map<String, Integer> accountsByProvider,
            int registered24h,
            int registered7d,
            int registered30d,
            int logins24h,
            int logins7d,
            int logins30d
    ) {
    }

    /** 汇总统计。在线 / bot 连接数由调用方补充（属运行时状态，不在库里）。 */
    public Stats stats() {
        long now = System.currentTimeMillis() / 1000L;
        long day = 86_400L;
        return new Stats(
                storage.accounts().count(),
                storage.accounts().countByStatus(),
                storage.bindings().countAccountsByType(),
                storage.accounts().countCreatedSince(now - day),
                storage.accounts().countCreatedSince(now - 7 * day),
                storage.accounts().countCreatedSince(now - 30 * day),
                storage.loginHistory().countByResultSince(LoginHistory.SUCCESS, now - day),
                storage.loginHistory().countByResultSince(LoginHistory.SUCCESS, now - 7 * day),
                storage.loginHistory().countByResultSince(LoginHistory.SUCCESS, now - 30 * day));
    }

    /**
     * 活跃度统计：日活 / 周活 / 月活（按「当天有成功登录」去重账号数）+ 注册趋势。
     *
     * @param rangeDays 趋势天数（1-90），0 表示只返回聚合值不返回趋势
     */
    public Activity activity(int rangeDays) {
        long now = System.currentTimeMillis() / 1000L;
        long day = 86_400L;
        int dau = storage.loginHistory().countDistinctAccountsSince(LoginHistory.SUCCESS, now - day);
        int wau = storage.loginHistory().countDistinctAccountsSince(LoginHistory.SUCCESS, now - 7 * day);
        int mau = storage.loginHistory().countDistinctAccountsSince(LoginHistory.SUCCESS, now - 30 * day);
        List<DailyActivity> trend = List.of();
        if (rangeDays > 0) {
            List<DailyActivity> mapped = new ArrayList<>();
            for (var entry : storage.loginHistory().dailySuccess(now - rangeDays * day)) {
                mapped.add(new DailyActivity(entry.date(), entry.logins(), entry.accounts()));
            }
            trend = mapped;
        }
        return new Activity(dau, wau, mau, trend);
    }

    /** 活跃度视图。{@code trend} 按日期升序（UTC 分桶）。 */
    public record Activity(int dau, int wau, int mau, List<DailyActivity> trend) {
    }

    /** 单日活跃度：成功登录次数与去重账号数。 */
    public record DailyActivity(String date, int logins, int accounts) {
    }

    // ------------------------------------------------------------------ 内部

    private Optional<Account> resolveAccount(long accountId) {
        return cache.byId(accountId).or(() -> storage.accounts().findById(accountId));
    }

    /** 账号 id → 名称；账号已删除时为空。 */
    public Optional<String> accountName(long accountId) {
        return resolveAccount(accountId).map(Account::name);
    }
}
