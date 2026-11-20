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
    private volatile boolean authenticated;
    private volatile long authenticatedAt;
    private volatile Player player;
    private OnlineTimeTracker onlineTime;
    private boolean disconnected;

    /**
     * 「注册后必须绑定」场景下，放行本连接对某个账号执行 {@code /bind} 的凭据。
     * <p>
     * 只有两种来源：本连接刚注册的账号，或本连接通过密码校验但被绑定要求拦下的账号。
     * 避免仅凭「连接名匹配」就能给他人账号绑定邮箱/QQ（那会导致通过找回流程接管账号）。
     * 0 表示未放行。
     */
    private volatile long bindingUnlockAccountId;

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

    public long authenticatedAt() {
        return authenticatedAt;
    }

    public Player player() {
        return player;
    }

    synchronized void attach(Player player, OnlineTimeTracker onlineTime) {
        this.player = player;
        this.onlineTime = onlineTime;
        if (account != null && onlineTime != null) {
            onlineTime.connected(account.id());
        }
    }

    synchronized void disconnect() {
        if (!disconnected && account != null && onlineTime != null) {
            onlineTime.disconnected(account.id());
        }
        disconnected = true;
    }

    /** 标记为已认证（账号身份）。 */
    public synchronized boolean authenticate(Account account) {
        if (disconnected) {
            return false;
        }
        if (onlineTime != null && (this.account == null || this.account.id() != account.id())) {
            if (this.account != null) {
                onlineTime.disconnected(this.account.id());
            }
            onlineTime.connected(account.id());
        }
        this.account = account;
        this.authenticated = true;
        this.authenticatedAt = System.currentTimeMillis();
        return true;
    }

    public synchronized Account deauthenticate() {
        Account previous = account;
        if (!disconnected && previous != null && onlineTime != null) {
            onlineTime.disconnected(previous.id());
        }
        this.account = null;
        this.authenticated = false;
        this.authenticatedAt = 0L;
        return previous;
    }

    /** 放行本连接对该账号的预登录 {@code /bind}（见 {@link #preLoginBindingAllowed}）。 */
    public void allowPreLoginBinding(long accountId) {
        this.bindingUnlockAccountId = accountId;
    }

    public boolean preLoginBindingAllowed(long accountId) {
        return accountId != 0L && bindingUnlockAccountId == accountId;
    }
}
