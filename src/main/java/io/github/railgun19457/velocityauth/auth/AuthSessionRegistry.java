package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.proxy.Player;

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
 * （{@code PostLoginEvent}）再 {@link #attach}。key 统一使用玩家当前 profile 的 UUID。
 */
public final class AuthSessionRegistry {

    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();

    public void stage(UUID profileUuid, AuthSession session) {
        sessions.put(profileUuid, session);
    }

    /** 玩家对象就绪；找不到暂存会话时返回空。 */
    public Optional<AuthSession> attach(UUID profileUuid, Player player) {
        AuthSession session = sessions.get(profileUuid);
        if (session == null) {
            return Optional.empty();
        }
        session.player(player);
        return Optional.of(session);
    }

    public Optional<AuthSession> get(UUID profileUuid) {
        return Optional.ofNullable(sessions.get(profileUuid));
    }

    public Optional<AuthSession> get(Player player) {
        return get(player.getUniqueId());
    }

    public Optional<AuthSession> remove(UUID profileUuid) {
        return Optional.ofNullable(sessions.remove(profileUuid));
    }

    public List<AuthSession> authenticatedSessions() {
        return sessions.values().stream().filter(AuthSession::authenticated).toList();
    }

    public List<AuthSession> sessionsOfAccount(long accountId) {
        return sessions.values().stream()
                .filter(s -> s.account() != null && s.account().id() == accountId)
                .toList();
    }

    public Collection<AuthSession> all() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }
}
