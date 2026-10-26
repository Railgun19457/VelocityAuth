package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.platform.FloodgateHook;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;

import java.util.Locale;
import java.util.UUID;

/**
 * 把一次连接解析为「身份」（DESIGN 4.7）。
 * <p>
 * Java 端身份 = 连接名小写；基岩端身份 = Floodgate UUID（基岩玩家连接名往往是 {@code .Steve}，
 * 直接用连接名会导致所有基岩玩家互相顶号）。
 */
public final class IdentityResolver {

    private final FloodgateHook floodgate;

    public IdentityResolver(FloodgateHook floodgate) {
        this.floodgate = floodgate;
    }

    /**
     * @param kind       身份类型
     * @param identifier 身份标识（写入 {@code identities.identifier}）
     * @param key        内存态 key（重连绑定 / 会话保持用，含类型前缀避免跨类型碰撞）
     */
    public record Resolved(IdentityKind kind, String identifier, String key) {
        public boolean bedrock() {
            return kind == IdentityKind.BEDROCK;
        }
    }

    public Resolved resolve(UUID uuid, String connectionName) {
        if (floodgate != null && floodgate.isFloodgatePlayer(uuid)) {
            String identifier = uuid.toString();
            return new Resolved(IdentityKind.BEDROCK, identifier, "bedrock:" + identifier);
        }
        String lower = connectionName == null ? "" : connectionName.toLowerCase(Locale.ROOT);
        return new Resolved(IdentityKind.JAVA, lower, "java:" + lower);
    }

    public boolean floodgateAvailable() {
        return floodgate != null && floodgate.available();
    }
}
