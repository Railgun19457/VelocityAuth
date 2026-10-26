package io.github.railgun19457.velocityauth.platform;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.Optional;
import java.util.UUID;

/**
 * Geyser / Floodgate 软依赖（DESIGN 4.7 / IMPLEMENTATION 5.5）。
 * <p>
 * Floodgate 未安装时必须**完全降级**：所有方法返回 false/empty，绝不抛错。
 * 因此对 Floodgate API 的引用全部包在 try/catch(Throwable) 内，避免 NoClassDefFoundError 外泄。
 */
public class FloodgateHook {

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

    /** 基岩端 XUID（用于展示；身份标识统一用 Floodgate UUID）。 */
    public Optional<String> xuid(UUID uuid) {
        if (!available || uuid == null) {
            return Optional.empty();
        }
        try {
            var player = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayer(uuid);
            return player == null ? Optional.empty() : Optional.ofNullable(player.getXuid());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
