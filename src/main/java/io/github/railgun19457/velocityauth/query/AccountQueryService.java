package io.github.railgun19457.velocityauth.query;

import io.github.railgun19457.velocityauth.auth.AccountCache;
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
            String premiumUuid,
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
                account.premiumUuid() == null ? null : account.premiumUuid().toString(),
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

    // ------------------------------------------------------------------ 内部

    private Optional<Account> resolveAccount(long accountId) {
        return cache.byId(accountId).or(() -> storage.accounts().findById(accountId));
    }

    /** 账号 id → 名称；账号已删除时为空。 */
    public Optional<String> accountName(long accountId) {
        return resolveAccount(accountId).map(Account::name);
    }
}
