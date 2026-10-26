package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.storage.model.Account;

/**
 * 一次玩家连接的运行时认证状态。
 * <p>
 * {@code account != null} 表示已认证；未认证玩家停留在登录服。
 */
public final class AuthSession {

    private final String connectionName;
    private final String connectionNameLower;
    private final String ip;
    private final long connectedAt;

    private volatile Account account;
    private volatile boolean premium;
    private volatile boolean authenticated;
    private volatile long authenticatedAt;
    private volatile Player player;

    public AuthSession(String connectionName, String ip) {
        this.connectionName = connectionName;
        this.connectionNameLower = connectionName == null ? "" : connectionName.toLowerCase(java.util.Locale.ROOT);
        this.ip = ip;
        this.connectedAt = System.currentTimeMillis();
    }

    public String connectionName() {
        return connectionName;
    }

    public String connectionNameLower() {
        return connectionNameLower;
    }

    public String ip() {
        return ip;
    }

    public long connectedAt() {
        return connectedAt;
    }

    public Account account() {
        return account;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public boolean premium() {
        return premium;
    }

    public long authenticatedAt() {
        return authenticatedAt;
    }

    public Player player() {
        return player;
    }

    public void player(Player player) {
        this.player = player;
    }

    /** 标记为已认证（账号身份）。 */
    public void authenticate(Account account) {
        this.account = account;
        this.premium = false;
        this.authenticated = true;
        this.authenticatedAt = System.currentTimeMillis();
    }

    /** 标记为正版玩家（保留真实 profile；account 可能为空）。 */
    public void authenticatePremium(Account account) {
        this.account = account;
        this.premium = true;
        this.authenticated = true;
        this.authenticatedAt = System.currentTimeMillis();
    }

    public void deauthenticate() {
        this.account = null;
        this.premium = false;
        this.authenticated = false;
        this.authenticatedAt = 0L;
    }

    public String displayName() {
        return account != null ? account.name() : connectionName;
    }
}
