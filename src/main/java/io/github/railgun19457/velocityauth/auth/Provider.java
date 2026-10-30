package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 绑定渠道（provider）：内置邮箱，或任意社交平台（由 bot 适配器实现）。
 * <p>
 * 平台语义（房间、成员、账号格式）全部由 bot 侧负责；插件只保留平台无关的接受规则。
 *
 * @param id              渠道标识，如 {@code email} / {@code qq} / {@code discord}
 * @param kind            {@code EMAIL}（内置 SMTP）/ {@code SOCIAL}（外部 bot）
 * @param displayName     展示名（如 {@code QQ}）
 * @param enabled         是否启用
 * @param maxAccounts     同一标识最多绑定几个账号
 * @param cooldownMinutes 解绑/注销后的再绑定冷却（分钟，0 = 不启用）
 * @param codeLength      验证码长度（仅 SOCIAL；email 固定数字码）
 */
public record Provider(String id, Kind kind, String displayName, boolean enabled,
                       int maxAccounts, int cooldownMinutes, int codeLength) {

    public enum Kind { EMAIL, SOCIAL }

    /** 内置邮箱渠道的 id。 */
    public static final String EMAIL_ID = "email";

    public boolean email() {
        return kind == Kind.EMAIL;
    }

    public boolean social() {
        return kind == Kind.SOCIAL;
    }

    /** 从配置解析全部渠道：内置 email + 已配置的 social。 */
    public static List<Provider> all(VelocityAuthConfig cfg) {
        List<Provider> out = new ArrayList<>();
        VelocityAuthConfig.EmailBinding email = cfg.binding().email();
        out.add(new Provider(EMAIL_ID, Kind.EMAIL, "email", email.enabled(),
                email.maxAccounts(), email.cooldownMinutes(), 0));
        for (Map.Entry<String, VelocityAuthConfig.SocialBinding> entry : cfg.binding().social().entrySet()) {
            VelocityAuthConfig.SocialBinding social = entry.getValue();
            out.add(new Provider(entry.getKey().toLowerCase(Locale.ROOT), Kind.SOCIAL, social.displayName(),
                    social.enabled(), social.maxAccounts(), social.cooldownMinutes(), social.codeLength()));
        }
        return out;
    }

    public static List<Provider> social(VelocityAuthConfig cfg) {
        return all(cfg).stream().filter(Provider::social).toList();
    }

    public static List<String> ids(VelocityAuthConfig cfg) {
        return all(cfg).stream().map(Provider::id).toList();
    }

    public static Optional<Provider> find(VelocityAuthConfig cfg, String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.toLowerCase(Locale.ROOT);
        return all(cfg).stream().filter(provider -> provider.id().equals(key)).findFirst();
    }

    public static boolean exists(VelocityAuthConfig cfg, String id) {
        return find(cfg, id).isPresent();
    }
}
