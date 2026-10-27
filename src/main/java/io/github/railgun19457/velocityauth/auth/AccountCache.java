package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.storage.AccountRepository;
import io.github.railgun19457.velocityauth.storage.IdentityRepository;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.Identity;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号 / 身份内存索引。
 * <p>
 * {@code GameProfileRequestEvent} 是 @AwaitingEvent，代理会等待其返回，
 * 因此登录热路径只允许查内存。
 */
public final class AccountCache {

    private final AccountRepository accounts;
    private final IdentityRepository identities;
    private final PluginLogger log;

    private final Map<String, Account> byNameLower = new ConcurrentHashMap<>();
    private final Map<Long, Account> byId = new ConcurrentHashMap<>();
    private final Map<String, Long> identityIndex = new ConcurrentHashMap<>();

    public AccountCache(AccountRepository accounts, IdentityRepository identities, PluginLogger log) {
        this.accounts = accounts;
        this.identities = identities;
        this.log = log;
    }

    /** 启动时全量预热。 */
    public void load() {
        byNameLower.clear();
        byId.clear();
        identityIndex.clear();
        for (Account account : accounts.findAll()) {
            put(account);
        }
        for (Identity identity : identities.findAll()) {
            identityIndex.put(identityKey(identity.kind(), identity.identifier()), identity.accountId());
        }
        log.info("账号缓存已预热：{} 个账号，{} 条身份映射", byNameLower.size(), identityIndex.size());
    }

    public Optional<Account> byNameLower(String nameLower) {
        return Optional.ofNullable(byNameLower.get(nameLower));
    }

    public Optional<Account> byId(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Account> byIdentity(IdentityKind kind, String identifier) {
        Long accountId = identityIndex.get(identityKey(kind, identifier));
        return accountId == null ? Optional.empty() : byId(accountId);
    }

    public boolean existsByNameLower(String nameLower) {
        return byNameLower.containsKey(nameLower);
    }

    public void put(Account account) {
        byId.put(account.id(), account);
        byNameLower.put(account.nameLower(), account);
    }

    public void indexIdentity(IdentityKind kind, String identifier, long accountId) {
        identityIndex.put(identityKey(kind, identifier), accountId);
    }

    public void remove(long accountId) {
        Account removed = byId.remove(accountId);
        if (removed != null) {
            byNameLower.remove(removed.nameLower());
        }
        identityIndex.values().removeIf(value -> value == accountId);
    }

    public int size() {
        return byNameLower.size();
    }

    public int identityCount() {
        return identityIndex.size();
    }

    private static String identityKey(IdentityKind kind, String identifier) {
        return kind.dbValue() + ":" + identifier;
    }
}
