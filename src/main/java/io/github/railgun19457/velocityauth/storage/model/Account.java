package io.github.railgun19457.velocityauth.storage.model;

import java.util.UUID;

/**
 * 账号（规范身份）。
 *
 * @param id           自增主键
 * @param name         显示名（保留注册时大小写）
 * @param nameLower    规范名（小写，唯一）
 * @param uuid         规范 UUID（由小写规范名派生）
 * @param passwordHash bcrypt 哈希
 * @param status       账号状态
 * @param lastServer   上次所在子服
 * @param createdAt    注册时间（unix 秒）
 * @param lastLoginAt  上次登录时间（unix 秒，可空）
 * @param lastIp       上次登录 IP（可空）
 */
public record Account(
        long id,
        String name,
        String nameLower,
        UUID uuid,
        String passwordHash,
        AccountStatus status,
        String lastServer,
        long createdAt,
        Long lastLoginAt,
        String lastIp
) {
    public boolean locked() {
        return status == AccountStatus.LOCKED;
    }
}
