package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.AuditEntry;

/**
 * 审计上下文：谁、从哪、经哪个 bot 客户端发起了这次操作。
 * <p>
 * 仅用于**追溯**，不参与鉴权——鉴权由 bot 通道的配对/会话与 {@code bot.admin.*} 配置决定。
 *
 * @param actor    操作者标识（玩家名 / console / bot 侧管理员 id）
 * @param source   来源，取值见 {@code AuditEntry.SOURCE_*}
 * @param clientId 发起操作的 bot 客户端 id（仅 source=bot 时有值）
 */
public record AuditContext(String actor, String source, String clientId) {

    /** 游戏内命令来源。 */
    public static AuditContext command(String actor) {
        return new AuditContext(actor, AuditEntry.SOURCE_COMMAND, null);
    }

    /** 插件内部自动动作（无操作者）。 */
    public static AuditContext plugin() {
        return new AuditContext(null, AuditEntry.SOURCE_PLUGIN, null);
    }

    /** bot 管理接口来源。 */
    public static AuditContext bot(String actor, String clientId) {
        return new AuditContext(actor, AuditEntry.SOURCE_BOT, clientId);
    }
}
