package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import io.github.railgun19457.velocityauth.verify.VerificationChannel;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;
import io.github.railgun19457.velocityauth.verify.VerificationRequest;
import io.github.railgun19457.velocityauth.verify.VerificationService;
import io.github.railgun19457.velocityauth.verify.VerificationSession;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 找回密码编排（DESIGN 6.3）。
 * <p>
 * 防账号枚举：无论账号是否存在，调用方都返回同一提示；只有账号存在且有所选渠道的绑定时才会真正发码。
 * 验证通过后发放一次性的"重置授权"（内存态，短 TTL），供 {@code /resetpassword} 消费。
 */
public final class RecoveryService {

    /** 重置授权有效期。 */
    public static final Duration GRANT_TTL = Duration.ofMinutes(5);

    private final Supplier<VelocityAuthConfig> config;
    private final StorageService storage;
    private final VerificationService verifications;
    private final AsyncExecutor async;
    private final PluginLogger log;

    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();

    public record Grant(long accountId, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    /** 发起结果；{@code accepted=false} 表示静默处理（账号不存在或没有该绑定）。 */
    public record Start(boolean accepted, Account account, VerificationSession session,
                        CompletableFuture<io.github.railgun19457.velocityauth.verify.VerificationResult> completion) {
    }

    public RecoveryService(Supplier<VelocityAuthConfig> config, StorageService storage,
                           VerificationService verifications, AsyncExecutor async, PluginLogger log) {
        this.config = config;
        this.storage = storage;
        this.verifications = verifications;
        this.async = async;
        this.log = log;
    }

    public CompletableFuture<Start> request(String accountName, BindingType channel, UUID playerUuid,
                                            String connectionName, String ip,
                                            Consumer<io.github.railgun19457.velocityauth.verify.VerificationDispatch>
                                                    onDispatch) {
        return async.supply(() -> {
            String nameLower = AuthService.normalize(accountName);
            Optional<Account> found = storage.accounts().findByNameLower(nameLower);
            if (found.isEmpty()) {
                // 防枚举：静默返回
                return new Start(false, null, null, null);
            }
            Account account = found.get();
            if (account.locked()) {
                return new Start(false, null, null, null);
            }
            Optional<io.github.railgun19457.velocityauth.storage.model.Binding> binding =
                    storage.bindings().find(account.id(), channel);
            if (binding.isEmpty()) {
                return new Start(false, null, null, null);
            }
            String target = binding.get().identifier();

            VerificationChannel verificationChannel = channel == BindingType.EMAIL
                    ? VerificationChannel.EMAIL : VerificationChannel.QQ;
            VerificationRequest request = VerificationRequest
                    .builder(VerificationPurpose.RECOVER, verificationChannel, playerUuid)
                    .accountName(nameLower)
                    .connectionName(connectionName)
                    .ip(ip)
                    .target(verificationChannel == VerificationChannel.EMAIL ? target : null)
                    .data(Map.of("account_id", Long.toString(account.id())))
                    .onDispatch(onDispatch)
                    .build();

            VerificationService.Start start = verifications.start(request);
            if (!start.accepted()) {
                return new Start(false, account, null, null);
            }
            CompletableFuture<io.github.railgun19457.velocityauth.verify.VerificationResult> completion =
                    start.completion().thenApply(result -> {
                        if (result.completed()) {
                            grant(playerUuid, account.id());
                        }
                        return result;
                    });
            return new Start(true, account, start.session(), completion);
        });
    }

    /** 发放一次重置授权（同一玩家只保留最新的一个）。 */
    public void grant(UUID playerUuid, long accountId) {
        grants.put(playerUuid, new Grant(accountId, System.currentTimeMillis() + GRANT_TTL.toMillis()));
    }

    /** 消费授权（消费即删）。 */
    public Optional<Long> consumeGrant(UUID playerUuid) {
        Grant grant = grants.remove(playerUuid);
        if (grant == null || grant.expired()) {
            return Optional.empty();
        }
        return Optional.of(grant.accountId());
    }

    public boolean hasGrant(UUID playerUuid) {
        Grant grant = grants.get(playerUuid);
        return grant != null && !grant.expired();
    }

    public void revokeGrant(UUID playerUuid) {
        grants.remove(playerUuid);
    }

    public int pendingGrants() {
        return grants.size();
    }

    public void purgeExpired() {
        Iterator<Map.Entry<UUID, Grant>> it = grants.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expired()) {
                it.remove();
            }
        }
    }
}
