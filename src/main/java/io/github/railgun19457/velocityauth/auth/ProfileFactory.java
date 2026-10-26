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
     * 规范 UUID：{@code UUID.nameUUIDFromBytes("OfflinePlayer:" + 小写规范名)}，
     * 与 {@code GameProfile.forOfflinePlayer} 一致（ADR-2）。
     */
    public static UUID offlineUuid(String nameLower) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + nameLower).getBytes(StandardCharsets.UTF_8));
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
