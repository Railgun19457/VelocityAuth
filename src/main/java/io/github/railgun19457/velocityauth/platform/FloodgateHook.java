package io.github.railgun19457.velocityauth.platform;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Geyser / Floodgate 软依赖。
 * <p>
 * Floodgate 未安装时必须**完全降级**：所有方法返回 false/empty，绝不抛错。
 * 因此对 Floodgate API 的引用全部包在 try/catch(Throwable) 内，避免 NoClassDefFoundError 外泄。
 */
public class FloodgateHook {

    /** 建立 Floodgate 账号关联的最长等待。 */
    private static final long LINK_TIMEOUT_SECONDS = 5;

    private final boolean available;
    private final PluginLogger log;

    protected FloodgateHook(boolean available, PluginLogger log) {
        this.available = available;
        this.log = log;
    }

    public static FloodgateHook create(ProxyServer server, PluginLogger log) {
        boolean loaded = server.getPluginManager().isLoaded("floodgate");
        if (!loaded) {
            log.info("未检测到 Floodgate，基岩端功能降级（仅 Java 端可用）");
            return new FloodgateHook(false, log);
        }
        try {
            // 触发一次 API 访问，确认类确实可加载
            Object api = org.geysermc.floodgate.api.FloodgateApi.getInstance();
            if (api == null) {
                log.warn("Floodgate 已加载但 FloodgateApi.getInstance() 返回 null，基岩端功能降级");
                return new FloodgateHook(false, log);
            }
            log.info("已检测到 Floodgate，启用基岩端身份识别");
            return new FloodgateHook(true, log);
        } catch (Throwable t) {
            log.warn("Floodgate 已加载但 API 不可用（{}），基岩端功能降级", t.toString());
            return new FloodgateHook(false, log);
        }
    }

    public boolean available() {
        return available;
    }

    /** 该 UUID 是否来自 Floodgate（基岩端）。 */
    public boolean isFloodgatePlayer(UUID uuid) {
        if (!available || uuid == null) {
            return false;
        }
        try {
            var api = org.geysermc.floodgate.api.FloodgateApi.getInstance();
            return api.isFloodgateId(uuid) || api.isFloodgatePlayer(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Floodgate 的「稳定 ID」：由 XUID 派生（{@code FloodgatePlayer#getJavaUniqueId()}），
     * **与玩家是否已关联账号无关**。
     * <p>
     * 基岩身份用它做标识：未关联时它就等于玩家当前 UUID；已关联时玩家 UUID 会变成关联的
     * Java UUID，而它保持不变，因此关联前后 {@code identity.key()} 一致，重连绑定仍能命中。
     */
    public Optional<UUID> stableJavaId(UUID playerUuid) {
        if (!available || playerUuid == null) {
            return Optional.empty();
        }
        try {
            var player = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayer(playerUuid);
            return player == null ? Optional.empty() : Optional.ofNullable(player.getJavaUniqueId());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Floodgate 的 player-link 是否可用（已启用且允许关联）。 */
    public boolean linkingAvailable() {
        if (!available) {
            return false;
        }
        try {
            var link = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayerLink();
            return link != null && link.isEnabledAndAllowed();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解除某基岩身份与账号的关联（Floodgate player-link）。
     * <p>
     * 用于「新绑定顶掉旧绑定」：一个账号同时只保留一个基岩身份。
     * 不阻塞、不抛错。
     */
    public CompletableFuture<Void> unlinkAccount(UUID bedrockUuid) {
        if (!available || bedrockUuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            var link = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayerLink();
            if (link == null || !link.isEnabledAndAllowed()) {
                return CompletableFuture.completedFuture(null);
            }
            return link.unlinkPlayer(bedrockUuid)
                    .orTimeout(LINK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(t -> {
                        log.warn("Floodgate 解除关联失败：{}", t.toString());
                        return null;
                    });
        } catch (Throwable t) {
            log.warn("Floodgate 解除关联不可用：{}", t.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 把基岩玩家的 Floodgate 身份关联到账号（Floodgate player-link）。
     * <p>
     * 关联后 Floodgate 会在**下次握手**用 {@code getCorrectUniqueId()/getCorrectUsername()} 注入账号
     * 身份，且其内部 {@code getPlayer(uuid)} 会兜底比对 {@code getCorrectUniqueId()} 命中该 UUID，
     * 因此不会再被判定为「非 Floodgate 玩家」而断开。
     * <p>
     * 不阻塞、不抛错：未启用 / 失败 / 超时都只记录日志并返回已完成的 future。
     */
    public CompletableFuture<Void> linkAccount(UUID bedrockUuid, UUID javaUuid, String javaUsername) {
        if (!available || bedrockUuid == null || javaUuid == null || javaUsername == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            var link = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayerLink();
            if (link == null || !link.isEnabledAndAllowed()) {
                log.warn("Floodgate player-link 未启用，跳过账号关联（基岩端将沿用 Floodgate UUID）");
                return CompletableFuture.completedFuture(null);
            }
            return link.linkPlayer(bedrockUuid, javaUuid, javaUsername)
                    .orTimeout(LINK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(t -> {
                        log.warn("Floodgate 账号关联失败：{}", t.toString());
                        return null;
                    });
        } catch (Throwable t) {
            log.warn("Floodgate 账号关联不可用：{}", t.toString());
            return CompletableFuture.completedFuture(null);
        }
    }
}
