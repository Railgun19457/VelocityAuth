package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.verify.VerificationDispatch;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;

import java.util.function.Consumer;

/**
 * 验证发起阶段的玩家提示（统一渲染，避免各命令重复）。
 */
public final class VerificationPrompts {

    private VerificationPrompts() {
    }

    /**
     * 构造派发回调。QQ 渠道的提示在 {@link #sendQqInstruction} 中单独发送（需要验证码）。
     *
     * @param purpose 验证目的，用于把「操作占位符」{@code {action}} 填成注册 / 绑定 / 找回等
     */
    public static Consumer<VerificationDispatch> dispatchHandler(VelocityAuthPlugin plugin, Player player,
                                                                String email, VerificationPurpose purpose) {
        long minutes = Math.max(1, plugin.config().verification().ttlSeconds() / 60);
        String action = actionLabel(plugin, purpose);
        return dispatch -> {
            switch (dispatch) {
                case EMAIL_SENT -> player.sendMessage(email == null
                        ? plugin.lang().msg("verification.email-sent-bound", "action", action, "minutes", minutes)
                        : plugin.lang().msg("verification.email-sent", "action", action, "email", email,
                                "minutes", minutes));
                case EMAIL_FAILED -> player.sendMessage(plugin.lang().msg("verification.email-failed"));
                case EMAIL_UNAVAILABLE -> player.sendMessage(plugin.lang().msg("verification.email-unavailable"));
                case QQ_UNAVAILABLE -> player.sendMessage(plugin.lang().msg("verification.qq-unavailable"));
                case RATE_LIMITED -> player.sendMessage(plugin.lang().msg("verification.rate-limited"));
                case QQ_WATCHING -> {
                    // 由调用方在拿到 session 后发送带验证码的提示
                }
            }
        };
    }

    /** 操作名称（语言文件 {@code verification.actions.*}），用于 {@code {action}} 占位符。 */
    public static String actionLabel(VelocityAuthPlugin plugin, VerificationPurpose purpose) {
        return plugin.lang().rawText(purpose.actionLangKey());
    }

    /** 游戏内出码（点击复制），供玩家粘贴到 QQ 群/私聊。 */
    public static void sendQqInstruction(VelocityAuthPlugin plugin, Player player, String code,
                                         VerificationPurpose purpose) {
        player.sendMessage(plugin.lang().msg("verification.qq-instruction",
                "action", actionLabel(plugin, purpose), "channels", qqChannels(plugin)));
        player.sendMessage(plugin.lang().raw("verification.code-line", "code", code));
        player.sendMessage(plugin.lang().msg("verification.code-prompt"));
    }

    /** 收码位置文案：QQ 群号，配置了私聊号时一并展示。 */
    public static String qqChannels(VelocityAuthPlugin plugin) {
        String group = plugin.config().qqbot().groupId();
        StringBuilder out = new StringBuilder("QQ 群 ").append(group == null || group.isBlank() ? "(未配置)" : group);
        String privateNumber = plugin.config().qqbot().privateNumber();
        if (privateNumber != null && !privateNumber.isBlank()) {
            out.append(" / 私聊 ").append(privateNumber);
        }
        return out.toString();
    }

    /**
     * 邮箱域名规则提示：白名单 / 黑名单 / 不限。
     * <p>
     * 用于玩家「选择绑定邮箱」时告知可用的邮箱类型。
     */
    public static net.kyori.adventure.text.Component emailDomainHint(VelocityAuthPlugin plugin) {
        VelocityAuthConfig.Domain domain = plugin.config().binding().email().domain();
        if (domain.mode() == VelocityAuthConfig.Domain.Mode.OFF || domain.list().isEmpty()) {
            return plugin.lang().msg("command.bind.email-domains-any");
        }
        return plugin.lang().msg(
                domain.mode() == VelocityAuthConfig.Domain.Mode.WHITELIST
                        ? "command.bind.email-domains-allow" : "command.bind.email-domains-deny",
                "domains", String.join(", ", domain.list()));
    }
}
