package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮箱地址校验与域名规则（DESIGN 6.1 / 12.1）。
 * <p>
 * 普通名单条目精确匹配域名，{@code regex:} 条目匹配规范化后的完整邮箱地址。
 */
public final class EmailRules {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9]([A-Za-z0-9.\\-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}$");

    public enum DomainVerdict {
        /** 允许。 */
        ALLOWED,
        /** 邮箱格式不合法。 */
        INVALID_EMAIL,
        /** 地址被白名单或黑名单规则拒绝。 */
        DOMAIN_NOT_ALLOWED
    }

    private EmailRules() {
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String email) {
        if (email == null) {
            return false;
        }
        String value = email.trim();
        if (value.isEmpty() || value.length() > 254) {
            return false;
        }
        return EMAIL_PATTERN.matcher(value).matches();
    }

    public static String domainOf(String email) {
        if (email == null) {
            return "";
        }
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** 校验格式与邮箱名单。 */
    public static DomainVerdict check(String email, VelocityAuthConfig.Domain rule) {
        if (!isValid(email)) {
            return DomainVerdict.INVALID_EMAIL;
        }
        if (rule == null || rule.mode() == VelocityAuthConfig.Domain.Mode.OFF) {
            return DomainVerdict.ALLOWED;
        }
        boolean listed = rule.matches(domainOf(email), normalize(email));
        return switch (rule.mode()) {
            case WHITELIST -> listed ? DomainVerdict.ALLOWED : DomainVerdict.DOMAIN_NOT_ALLOWED;
            case BLACKLIST -> listed ? DomainVerdict.DOMAIN_NOT_ALLOWED : DomainVerdict.ALLOWED;
            default -> DomainVerdict.ALLOWED;
        };
    }
}
