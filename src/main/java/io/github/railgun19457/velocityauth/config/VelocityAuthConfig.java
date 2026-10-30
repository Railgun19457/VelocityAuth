package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.storage.model.BindingType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 插件配置模型（不可变）。由 {@link #from(ConfigNode)} 从 YAML 树映射而来。
 */
public record VelocityAuthConfig(
        String language,
        Database database,
        Servers servers,
        Auth auth,
        Registration registration,
        Binding binding,
        Verification verification,
        Smtp smtp,
        QqBot qqbot,
        Proxy proxy,
        Security security
) {

    // ---------------------------------------------------------------- database

    public record Database(Type type, Sqlite sqlite, Mysql mysql) {
        public enum Type { SQLITE, MYSQL }
    }

    public record Sqlite(String file) {
    }

    public record Mysql(String host, int port, String database, String username, String password) {
    }

    // ---------------------------------------------------------------- servers

    public record Servers(String login, String defaultServer, List<String> allowed, boolean rememberLast) {
    }

    // ---------------------------------------------------------------- auth

    public record Auth(AccountName accountName, Password password, int bcryptCost, Session session, Premium premium,
                       Bedrock bedrock) {
    }

    public record AccountName(int minLength, int maxLength, Pattern pattern) {
    }

    public record Password(int minLength, int maxLength) {
    }

    public record Session(boolean enabled, int ttlMinutes, boolean bindToIp) {
    }

    public record Premium(boolean enabled, boolean keepRealUuid, boolean requirePassword, NameConflict nameConflict) {
        public enum NameConflict { FIRST_COME, PREMIUM_PRIORITY }
    }

    /**
     * 基岩端（Floodgate）行为。
     *
     * @param autoLink 基岩玩家认证成功后，是否自动把其 Floodgate 身份关联到账号
     *                 （见 {@code FloodgateHook#linkAccount}）。关联后 Floodgate 会在下次握手
     *                 原生注入账号的 name/uuid，从而无需改写 UUID、也不会被 Floodgate 判为非
     *                 Floodgate 玩家而踢出。需要 Floodgate 端启用 player-link。
     */
    public record Bedrock(boolean autoLink) {
    }

    // ---------------------------------------------------------------- registration / binding

    /**
     * 注册行为与「注册后必须绑定」的要求。
     *
     * @param requireBinding     注册后必须完成的绑定渠道，玩家**任选其一**即可；
     *                           空列表表示无需绑定（注册完即可登录）。
     * @param unboundTtlMinutes  未绑定账号的存活时间（分钟），超时自动清除；0 表示不自动清除。
     */
    public record Registration(List<BindingType> requireBinding, int unboundTtlMinutes) {

        public boolean bindingRequired() {
            return !requireBinding.isEmpty();
        }
    }

    public record Binding(EmailBinding email, QqBinding qq) {
    }

    public record EmailBinding(boolean enabled, int maxAccountsPerEmail, Domain domain) {
    }

    public record Domain(Mode mode, List<String> list) {
        public enum Mode { OFF, WHITELIST, BLACKLIST }
    }

    public record QqBinding(boolean enabled, int maxAccountsPerQq) {
    }

    // ---------------------------------------------------------------- verification

    public record Verification(int emailCodeLength, int qqCodeLength, int ttlSeconds, int maxAttempts,
                               RateLimit rateLimit) {
    }

    public record RateLimit(int perIpPerMinute, int perAccountPerMinute) {
    }

    // ---------------------------------------------------------------- smtp

    public record Smtp(boolean enabled, String host, int port, boolean ssl, String username, String password,
                       String from, int timeoutSeconds) {
    }

    // ---------------------------------------------------------------- qq bot

    public record QqBot(boolean enabled, String listen, int port, String groupId, String privateNumber,
                        boolean requireGroupMember, Pairing pairing) {
    }

    public record Pairing(int ttlSeconds, int maxAttempts, String bootstrapCode) {
    }

    // ---------------------------------------------------------------- proxy / security

    public record Proxy(String address, int port) {
    }

    public record Security(int handshakeTimeoutSeconds, int clockSkewSeconds) {
    }

    // ---------------------------------------------------------------- mapping

    public static VelocityAuthConfig from(ConfigNode root) {
        String language = root.string("language", "zh_cn");

        ConfigNode db = root.section("database");
        String dbType = db.enumValue("type", "sqlite", "sqlite", "mysql");
        ConfigNode sqlite = db.section("sqlite");
        ConfigNode mysql = db.section("mysql");
        Database database = new Database(
                Database.Type.valueOf(dbType.toUpperCase()),
                new Sqlite(sqlite.string("file", "velocityauth.db")),
                new Mysql(
                        mysql.string("host", "127.0.0.1"),
                        mysql.intValue("port", 3306),
                        mysql.string("database", "velocityauth"),
                        mysql.string("username", "root"),
                        mysql.string("password", "")));

        ConfigNode sv = root.section("servers");
        Servers servers = new Servers(
                sv.string("login", "lobby"),
                sv.string("default", "main"),
                sv.stringList("allowed", List.of("main", "survival")),
                sv.bool("remember-last", true));

        ConfigNode authNode = root.section("auth");
        ConfigNode nameNode = authNode.section("account-name");
        String patternText = nameNode.string("pattern", "^[A-Za-z0-9_]+$");
        Pattern namePattern;
        try {
            namePattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            throw new ConfigException("auth.account-name.pattern", "正则表达式不合法：" + e.getDescription(), e);
        }
        AccountName accountName = new AccountName(
                nameNode.intValue("min-length", 3),
                nameNode.intValue("max-length", 16),
                namePattern);
        if (accountName.minLength() < 1 || accountName.minLength() > accountName.maxLength()) {
            throw new ConfigException("auth.account-name",
                    "min-length 必须 >= 1 且不大于 max-length（当前 " + accountName.minLength()
                            + " / " + accountName.maxLength() + "）");
        }

        ConfigNode pwNode = authNode.section("password");
        Password password = new Password(
                pwNode.intValue("min-length", 6),
                pwNode.intValue("max-length", 64));
        if (password.minLength() < 1 || password.minLength() > password.maxLength()) {
            throw new ConfigException("auth.password",
                    "min-length 必须 >= 1 且不大于 max-length（当前 " + password.minLength()
                            + " / " + password.maxLength() + "）");
        }

        int bcryptCost = authNode.intValue("bcrypt-cost", 10);
        if (bcryptCost < 4 || bcryptCost > 16) {
            throw new ConfigException("auth.bcrypt-cost", "建议取值范围 4-16（当前 " + bcryptCost + "）");
        }

        ConfigNode sessionNode = authNode.section("session");
        Session session = new Session(
                sessionNode.bool("enabled", false),
                sessionNode.intValue("ttl-minutes", 30),
                sessionNode.bool("bind-to-ip", true));

        ConfigNode premiumNode = authNode.section("premium");
        String conflict = premiumNode.enumValue("name-conflict", "first-come", "first-come", "premium-priority");
        Premium premium = new Premium(
                premiumNode.bool("enabled", true),
                premiumNode.bool("keep-real-uuid", true),
                premiumNode.bool("require-password", true),
                conflict.equals("first-come") ? Premium.NameConflict.FIRST_COME : Premium.NameConflict.PREMIUM_PRIORITY);

        Auth auth = new Auth(accountName, password, bcryptCost, session, premium,
                new Bedrock(authNode.section("bedrock").bool("auto-link", true)));

        ConfigNode regNode = root.section("registration");
        List<BindingType> requireBinding = new ArrayList<>();
        if (regNode.has("require-binding")) {
            for (String raw : regNode.stringListOrScalar("require-binding", List.of())) {
                BindingType type = BindingType.fromName(raw);
                if (type == null) {
                    throw new ConfigException("registration.require-binding",
                            "取值 \"" + raw + "\" 不合法，可选值：email | qq");
                }
                if (!requireBinding.contains(type)) {
                    requireBinding.add(type);
                }
            }
        } else if (regNode.has("gate")) {
            // 兼容旧配置：registration.gate = none | email | qq
            String legacy = regNode.enumValue("gate", "none", "none", "email", "qq");
            if (!legacy.equals("none")) {
                requireBinding.add(BindingType.fromName(legacy));
            }
        }
        int unboundTtlMinutes = regNode.intValue("unbound-ttl-minutes", 30);
        if (unboundTtlMinutes < 0) {
            throw new ConfigException("registration.unbound-ttl-minutes", "不能为负数（0 表示不自动清除）");
        }
        Registration registration = new Registration(List.copyOf(requireBinding), unboundTtlMinutes);

        ConfigNode bindNode = root.section("binding");
        ConfigNode emailNode = bindNode.section("email");
        ConfigNode domainNode = emailNode.section("domain");
        String domainMode = domainNode.enumValue("mode", "whitelist", "off", "whitelist", "blacklist");
        Domain domain = new Domain(
                switch (domainMode) {
                    case "off" -> Domain.Mode.OFF;
                    case "blacklist" -> Domain.Mode.BLACKLIST;
                    default -> Domain.Mode.WHITELIST;
                },
                domainNode.stringList("list", List.of()));
        EmailBinding emailBinding = new EmailBinding(
                emailNode.bool("enabled", true),
                emailNode.intValue("max-accounts-per-email", 1),
                domain);
        ConfigNode qqBindNode = bindNode.section("qq");
        QqBinding qqBinding = new QqBinding(
                qqBindNode.bool("enabled", true),
                qqBindNode.intValue("max-accounts-per-qq", 1));
        Binding binding = new Binding(emailBinding, qqBinding);

        ConfigNode verNode = root.section("verification");
        ConfigNode rateNode = verNode.section("rate-limit");
        Verification verification = new Verification(
                verNode.intValue("email-code-length", 6),
                verNode.intValue("qq-code-length", 6),
                verNode.intValue("ttl-seconds", 300),
                verNode.intValue("max-attempts", 5),
                new RateLimit(
                        rateNode.intValue("per-ip-per-minute", 5),
                        rateNode.intValue("per-account-per-minute", 3)));
        if (verification.ttlSeconds() <= 0) {
            throw new ConfigException("verification.ttl-seconds", "必须为正数");
        }
        if (verification.maxAttempts() <= 0) {
            throw new ConfigException("verification.max-attempts", "必须为正数");
        }

        ConfigNode smtpNode = root.section("smtp");
        Smtp smtp = new Smtp(
                smtpNode.bool("enabled", false),
                smtpNode.string("host", "smtp.qq.com"),
                smtpNode.intValue("port", 465),
                smtpNode.bool("ssl", true),
                smtpNode.string("username", ""),
                smtpNode.string("password", ""),
                smtpNode.string("from", "VelocityAuth <example@qq.com>"),
                smtpNode.intValue("timeout-seconds", 10));

        ConfigNode qqbotNode = root.section("qqbot");
        ConfigNode pairingNode = qqbotNode.section("pairing");
        QqBot qqbot = new QqBot(
                qqbotNode.bool("enabled", false),
                qqbotNode.string("listen", "0.0.0.0"),
                qqbotNode.intValue("port", 8765),
                qqbotNode.string("group-id", ""),
                qqbotNode.string("private-number", ""),
                qqbotNode.bool("require-group-member", true),
                new Pairing(
                        pairingNode.intValue("ttl-seconds", 600),
                        pairingNode.intValue("max-attempts", 5),
                        pairingNode.string("bootstrap-code", "")));

        ConfigNode proxyNode = root.section("proxy");
        Proxy proxy = new Proxy(
                proxyNode.string("address", ""),
                proxyNode.intValue("port", 0));

        ConfigNode secNode = root.section("security");
        Security security = new Security(
                secNode.intValue("handshake-timeout-seconds", 10),
                secNode.intValue("clock-skew-seconds", 120));

        return new VelocityAuthConfig(language, database, servers, auth, registration, binding, verification,
                smtp, qqbot, proxy, security);
    }
}
