package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.util.Addresses;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线认证会话注册表。
 * <p>
 * 登录握手阶段（{@code GameProfileRequestEvent}）先 {@link #stage}，玩家对象创建后
 * （{@code PostLoginEvent}）再 {@link #attach}。握手用地址和 UUID 匹配，
 * 已接入的会话用实际 Player 对象区分新旧连接。
 */
public final class AuthSessionRegistry {

    private static final long STAGED_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private record ConnectionKey(UUID profileUuid, InetSocketAddress address) {
    }

    private final Map<ConnectionKey, AuthSession> staged = new ConcurrentHashMap<>();
    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();
    private final OnlineTimeTracker onlineTime;

    public AuthSessionRegistry() {
        this(null);
    }

    public AuthSessionRegistry(OnlineTimeTracker onlineTime) {
        this.onlineTime = onlineTime;
    }

    public void stage(UUID profileUuid, InetSocketAddress address, AuthSession session) {
        staged.put(new ConnectionKey(profileUuid, address), session);
    }

    public synchronized Optional<AuthSession> attach(Player player) {
        UUID uuid = player.getUniqueId();
        ConnectionKey key = new ConnectionKey(uuid, player.getRemoteAddress());
        AuthSession previous = sessions.get(uuid);
        if (!player.isActive()) {
            staged.remove(key);
            return Optional.empty();
        }
        if (previous != null && previous.player() == player) {
            return Optional.of(previous);
        }
        AuthSession session = staged.remove(key);
        if (session == null) {
            session = new AuthSession(player.getUsername(), Addresses.ipOf(player));
        }
        session.attach(player, onlineTime);
        if (previous != null) {
            previous.disconnect();
        }
        sessions.put(uuid, session);
        return Optional.of(session);
    }

    public Optional<AuthSession> get(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()))
                .filter(session -> session.player() == player);
    }

    public synchronized Optional<AuthSession> remove(Player player) {
        UUID uuid = player.getUniqueId();
        staged.remove(new ConnectionKey(uuid, player.getRemoteAddress()));
        return get(player).map(session -> {
            sessions.remove(uuid);
            session.disconnect();
            return session;
        });
    }

    public void purgeStaged() {
        long cutoff = System.currentTimeMillis() - STAGED_TIMEOUT_MS;
        staged.values().removeIf(session -> session.connectedAt() < cutoff);
    }

    public List<AuthSession> authenticatedSessions() {
        return sessions.values().stream().filter(AuthSession::authenticated).toList();
    }

    public List<AuthSession> sessionsOfAccount(long accountId) {
        return sessions.values().stream()
                .filter(session -> {
                    var account = session.account();
                    return account != null && account.id() == accountId;
                })
                .toList();
    }

    public Collection<AuthSession> all() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }
}
