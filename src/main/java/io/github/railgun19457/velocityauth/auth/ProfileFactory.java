package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.util.GameProfile;
import io.github.railgun19457.velocityauth.storage.model.Account;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 账号 → GameProfile 转换与规范 UUID 计算。
 */
public final class ProfileFactory {

    private ProfileFactory() {
    }

    /**
     * 账号规范 UUID：{@code UUID.nameUUIDFromBytes("OfflinePlayer:" + 账号名)}，
     * 与 {@code GameProfile.forOfflinePlayer} 完全一致。
     * <p>
     * <b>名字按原样（大小写敏感）参与计算</b>，因为离线模式下 Velocity 就是这么算连接 UUID 的：
     * <pre>
     *   玩家连接 Tacker  → 连接 UUID = offline("Tacker")
     *   /register Tacker → 账号 UUID = offline("Tacker")   ← 一致，profile 重写是空操作
     * </pre>
     * 这样任何"以前没装认证插件"或"认证插件不改 UUID"的离线服，玩家历史数据（LuckPerms、
     * 经济、领地……）都能无缝接上。若改用小写名派生，名字含大写字母的玩家会换到另一个 UUID，
     * 历史数据全部对不上。
     * <p>
     * 大小写不敏感的账号模型不受影响：唯一性由 {@code accounts.name_lower} 约束保证，
     * 而 UUID 只在注册时算一次并落库，之后一律读库里的值（迁移还会直接用源库的 uuid，
     * 以覆盖改名等历史情况）。
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** 账号的规范 profile（保留原 profile 的皮肤等属性）。 */
    public static GameProfile profileFor(Account account, List<GameProfile.Property> properties) {
        return new GameProfile(account.uuid(), account.name(), properties);
    }

    /** 保持属性不变、仅替换 name/uuid。 */
    public static GameProfile rewrite(GameProfile original, UUID uuid, String name) {
        return new GameProfile(uuid, name, original.getProperties());
    }
}
