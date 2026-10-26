package io.github.railgun19457.velocityauth.auth;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重连绑定（内存态，TTL 90 秒，消费即删）。
 * <p>
 * key = (连接名小写, IP)，避免被同 IP 之外的他人复用。
 */
public final class PendingBindingStore {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(90);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public record Entry(long accountId, long expiresAt) {
        boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    public void put(String connectionNameLower, String ip, long accountId, Duration ttl) {
        long expiresAt = System.currentTimeMillis() + ttl.toMillis();
        entries.put(key(connectionNameLower, ip), new Entry(accountId, expiresAt));
    }

    public void put(String connectionNameLower, String ip, long accountId) {
        put(connectionNameLower, ip, accountId, DEFAULT_TTL);
    }

    /** 命中即删除；过期视为未命中。 */
    public Optional<Long> consume(String connectionNameLower, String ip) {
        String key = key(connectionNameLower, ip);
        Entry entry = entries.remove(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(entry.accountId());
    }

    /** 命中但不删除（用于查询提示）。 */
    public Optional<Long> peek(String connectionNameLower, String ip) {
        Entry entry = entries.get(key(connectionNameLower, ip));
        if (entry == null || entry.expired(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(entry.accountId());
    }

    public void remove(String connectionNameLower, String ip) {
        entries.remove(key(connectionNameLower, ip));
    }

    public void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expired(now)) {
                it.remove();
            }
        }
    }

    public int size() {
        return entries.size();
    }

    private static String key(String connectionNameLower, String ip) {
        return connectionNameLower + "|" + (ip == null ? "" : ip);
    }
}
