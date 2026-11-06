package io.github.railgun19457.velocityauth.admin;

import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthService;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.BindingService;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.AuditContext;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 账号管理写操作（管理命令与 bot {@code admin.*} 共用）。
 * <p>
 * 所有方法都在调用方给定的 {@link AuditContext} 下写审计，并保证：
 * <ul>
 *   <li>账号缓存 / 绑定索引 / 会话保持缓存同步更新</li>
 *   <li>踢人走代理主线程（{@code disconnect} 必须在事件循环里调用）</li>
 * </ul>
 * 鉴权不在这里：命令层用权限节点，bot 层用配对客户端 + {@code bot.admin} 配置。
 */
public final class AdminService {

    private final Supplier<VelocityAuthConfig> config;
    private final StorageService storage;
    private final AuthService auth;
    private final BindingService bindings;
    private final AuthSessionRegistry sessions;
    private final PluginLogger log;
    /** 把任务切回代理主线程（{@code Player#disconnect} 必须在事件循环里调用）。 */
    private final java.util.function.Consumer<Runnable> mainThread;

    public AdminService(Supplier<VelocityAuthConfig> config, StorageService storage, AuthService auth,
                        BindingService bindings, AuthSessionRegistry sessions,
                        java.util.function.Consumer<Runnable> mainThread, PluginLogger log) {
        this.config = config;
        this.storage = storage;
        this.auth = auth;
        this.bindings = bindings;
        this.sessions = sessions;
        this.mainThread = mainThread;
        this.log = log;
    }

    // ------------------------------------------------------------------ 查询辅助

    /** 按账号名（不区分大小写）查找账号；先查内存缓存再回源。 */
    public Optional<Account> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String lower = AuthService.normalize(name.trim());
        return auth.cache().byNameLower(lower)
                .or(() -> storage.accounts().findByNameLower(lower));
    }

    public Optional<Account> findById(long id) {
        return auth.cache().byId(id).or(() -> storage.accounts().findById(id));
    }

    /** 某账号当前在线且已认证的会话数。 */
    public int onlineCount(long accountId) {
        return sessions.sessionsOfAccount(accountId).stream().filter(AuthSession::authenticated).toList().size();
    }

    /** 全部在线已认证会话的账号 id 集合（列表页判断「是否在线」）。 */
    public java.util.Set<Long> onlineAccountIds() {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (AuthSession session : sessions.authenticatedSessions()) {
            if (session.account() != null) {
                out.add(session.account().id());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 写操作

    /** 锁定 / 解锁；锁定时同时踢出在线会话。 */
    public boolean setStatus(Account account, AccountStatus status, String ip, AuditContext audit) {
        String action = status == AccountStatus.LOCKED ? AuditEntry.ADMIN_LOCK : AuditEntry.ADMIN_UNLOCK;
        boolean updated = auth.setStatus(account.id(), status, ip, action, audit).join();
        if (!updated) {
            return false;
        }
        if (status == AccountStatus.LOCKED) {
            kick(account, "账号已被锁定", ip, audit);
        }
        return true;
    }
    /** 管理员重置密码（不校验旧密码）。 */
    public AuthOutcomes.ChangePassword resetPassword(Account account, String newPassword, String ip,
                                                     AuditContext audit) {
        return auth.forcePassword(account.id(), newPassword, ip, AuditEntry.ADMIN_RESET_PASSWORD, audit).join();
    }

    /** 解绑指定渠道。 */
    public AuthOutcomes.Bind unbind(Account account, String providerId, String ip, AuditContext audit) {
        return bindings.unbind(account, providerId, ip, AuditEntry.ADMIN_UNBIND, audit).join();
    }

    /** 删除账号（同时踢出在线会话、清理缓存与绑定索引、写绑定释放历史）。 */
    public boolean delete(Account account, String ip, AuditContext audit) {
        String detail = "account=" + account.name() + " by=" + audit.actor();
        boolean removed = auth.deleteAccount(account.id(), ip, AuditEntry.ADMIN_DELETE, detail, audit).join();
        if (removed) {
            kick(account, "账号已被删除", ip, audit);
        }
        return removed;
    }

    /** 清除会话保持（下次必须重新输入密码）。 */
    public void clearSessions(Account account, String ip, AuditContext audit) {
        auth.clearSessions(account.id());
        storage.audit().log(AuthService.nowSeconds(), account.id(), AuditEntry.ADMIN_CLEAR_SESSION, ip, null,
                audit.actor(), audit.source(), audit.clientId());
    }

    /**
     * 踢出账号的全部在线会话。
     * <p>
     * {@code Player#disconnect} 必须在代理主线程执行：这里通过 {@code mainThread} 把真正的
     * 断开动作切回事件循环，调用方（命令线程 / bot 通道线程）无需关心线程模型。
     *
     * @return 计划踢出的会话数（实际断开在主线程异步完成）
     */
    public int kick(Account account, String reason, String ip, AuditContext audit) {
        List<AuthSession> targets = new ArrayList<>(sessions.sessionsOfAccount(account.id()));
        mainThread.accept(() -> {
            int count = 0;
            for (AuthSession session : targets) {
                var player = session.player();
                if (player != null && player.isActive()) {
                    player.disconnect(Component.text(reason));
                    count++;
                }
            }
            storage.audit().log(AuthService.nowSeconds(), account.id(), AuditEntry.ADMIN_KICK, ip,
                    "reason=" + reason + " sessions=" + count,
                    audit.actor(), audit.source(), audit.clientId());
            log.info("管理员踢出账号 {} 的 {} 个会话（{}）", account.name(), count, audit.actor());
        });
        return targets.size();
    }

    /** 是否允许在 bot 管理接口里解绑（不允许把账号解绑成不满足绑定要求的状态）。 */
    public boolean canUnbind(Account account, String providerId) {
        return auth.bindingSatisfiedWithout(account, providerId);
    }
}
