package io.github.railgun19457.velocityauth.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        Bot bot,
        Proxy proxy,
        Security security
) {

    // ---------------------------------------------------------------- database

    public record Database(Type type, Sqlite sqlite, Mysql mysql,
                           int loginHistoryDays, int auditLogDays, int bindingHistoryDays) {
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

    public record Auth(AccountName accountName, Password password, int bcryptCost, Session session,
                       Bedrock bedrock) {
    }

    public record AccountName(int minLength, int maxLength, Pattern pattern) {
    }

    public record Password(int minLength, int maxLength) {
    }

    public record Session(boolean enabled, int ttlMinutes, boolean bindToIp) {
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
     * @param requireBinding     注册后必须完成的绑定渠道（provider id），玩家**任选其一**即可；
     *                           空列表表示无需绑定（注册完即可登录）。
     * @param unboundTtlMinutes  未绑定账号的存活时间（分钟），超时自动清除；0 表示不自动清除。
     */
    public record Registration(List<String> requireBinding, int unboundTtlMinutes) {

        public boolean bindingRequired() {
            return !requireBinding.isEmpty();
        }
    }

    /**
     * 绑定渠道配置：内置邮箱 + 任意社交渠道。
     *
     * @param email  内置邮箱（SMTP）渠道
     * @param social 社交渠道：provider id → 配置
     */
    public record Binding(EmailBinding email, Map<String, SocialBinding> social) {
    }

    public record EmailBinding(boolean enabled, int maxAccounts, int cooldownMinutes, Domain domain) {
    }

    /**
     * 社交渠道配置。平台相关的一切（监听房间、成员校验、账号格式）由 bot 侧负责。
     */
    public record SocialBinding(boolean enabled, String displayName, int maxAccounts, int cooldownMinutes,
                                int codeLength) {
    }

    public record Domain(Mode mode, List<String> list) {
        public enum Mode { OFF, WHITELIST, BLACKLIST }
    }

    // ---------------------------------------------------------------- verification

    public record Verification(int codeLength, int ttlSeconds, int maxAttempts, RateLimit rateLimit) {
    }

    public record RateLimit(int perIpPerMinute, int perAccountPerMinute) {
    }

    // ---------------------------------------------------------------- smtp

    public record Smtp(boolean enabled, String host, int port, boolean ssl, String username, String password,
                       String from, int timeoutSeconds) {
    }

    // ---------------------------------------------------------------- bot 通道

    public record Bot(boolean enabled, String listen, int port, Pairing pairing, Query query, Admin admin) {
    }

    public record Pairing(int ttlSeconds, int maxAttempts, String bootstrapCode) {
    }

    /**
     * bot 侧账号管理（{@code admin.*} 写接口，PROTOCOL.md 8.2）。
     *
     * @param enabled             是否启用；默认关闭。启用后插件在 capabilities 里声明 {@code admin.v1}
     * @param clients             允许发送 {@code admin.*} 的 client_id 白名单；空列表表示允许所有已配对客户端
     * @param readOnly            只读模式：拒绝一切写操作（便于先只开管理页观察）
     * @param maxPageSize         列表类接口单页最大条数
     * @param rateLimitPerMinute  单个客户端每分钟最多发送多少条 {@code admin.*}
     */
    public record Admin(boolean enabled, List<String> clients, boolean readOnly, int maxPageSize,
                        int rateLimitPerMinute) {
    }

    /**
     * bot 侧玩家信息查询接口（PROTOCOL.md 8）。
     *
     * @param enabled    是否启用；默认关闭。启用后插件在 capabilities 里声明 {@code query.v1}
     * @param maxResults 单次查询返回的最大条数（列表 / 历史记录）
     */
    public record Query(boolean enabled, int maxResults) {
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
        int loginHistoryDays = db.intValue("login-history-days", 90);
        int auditLogDays = db.intValue("audit-log-days", 90);
        int bindingHistoryDays = db.intValue("binding-history-days", 90);
        if (loginHistoryDays < 0) {
            throw new ConfigException("database.login-history-days", "不能为负数（0 表示永久保留）");
        }
        if (auditLogDays < 0) {
            throw new ConfigException("database.audit-log-days", "不能为负数（0 表示永久保留）");
        }
        if (bindingHistoryDays < 0) {
            throw new ConfigException("database.binding-history-days", "不能为负数（0 表示永久保留）");
        }
        Database database = new Database(
                Database.Type.valueOf(dbType.toUpperCase(Locale.ROOT)),
                new Sqlite(sqlite.string("file", "velocityauth.db")),
                new Mysql(
                        mysql.string("host", "127.0.0.1"),
                        mysql.intValue("port", 3306),
                        mysql.string("database", "velocityauth"),
                        mysql.string("username", "root"),
                        mysql.string("password", "")),
                loginHistoryDays, auditLogDays, bindingHistoryDays);

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

        Auth auth = new Auth(accountName, password, bcryptCost, session,
                new Bedrock(authNode.section("bedrock").bool("auto-link", true)));

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
                emailNode.intValue("max-accounts", 1),
                emailNode.intValue("cooldown-minutes", 0),
                domain);
        if (emailBinding.maxAccounts() < 1) {
            throw new ConfigException("binding.email.max-accounts", "必须为正数");
        }
        if (emailBinding.cooldownMinutes() < 0) {
            throw new ConfigException("binding.email.cooldown-minutes", "不能为负数（0 表示不启用冷却）");
        }

        // 社交渠道：binding.social.<provider-id>（任意平台，由 bot 适配器实现）
        Map<String, SocialBinding> social = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigNode> entry : bindNode.section("social").children().entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            String path = "binding.social." + entry.getKey();
            if (id.equals("email")) {
                throw new ConfigException(path, "email 是内置渠道，不能作为社交渠道配置");
            }
            ConfigNode node = entry.getValue();
            SocialBinding socialBinding = new SocialBinding(
                    node.bool("enabled", true),
                    node.string("display-name", entry.getKey()),
                    node.intValue("max-accounts", 1),
                    node.intValue("cooldown-minutes", 0),
                    node.intValue("code-length", 0));
            if (socialBinding.maxAccounts() < 1) {
                throw new ConfigException(path + ".max-accounts", "必须为正数");
            }
            if (socialBinding.cooldownMinutes() < 0) {
                throw new ConfigException(path + ".cooldown-minutes", "不能为负数（0 表示不启用冷却）");
            }
            social.put(id, socialBinding);
        }
        Binding binding = new Binding(emailBinding, Map.copyOf(social));

        // 已知渠道 id（email + social），用于校验 registration.require-binding
        Set<String> knownProviders = new LinkedHashSet<>();
        knownProviders.add("email");
        knownProviders.addAll(social.keySet());

        ConfigNode regNode = root.section("registration");
        List<String> requireBinding = new ArrayList<>();
        for (String raw : regNode.stringListOrScalar("require-binding", List.of())) {
            String id = raw.toLowerCase(Locale.ROOT);
            if (!knownProviders.contains(id)) {
                throw new ConfigException("registration.require-binding",
                        "取值 \"" + raw + "\" 不是已配置的绑定渠道，可选值：" + String.join(" | ", knownProviders));
            }
            if (!requireBinding.contains(id)) {
                requireBinding.add(id);
            }
        }
        int unboundTtlMinutes = regNode.intValue("unbound-ttl-minutes", 30);
        if (unboundTtlMinutes < 0) {
            throw new ConfigException("registration.unbound-ttl-minutes", "不能为负数（0 表示不自动清除）");
        }
        Registration registration = new Registration(List.copyOf(requireBinding), unboundTtlMinutes);

        ConfigNode verNode = root.section("verification");
        ConfigNode rateNode = verNode.section("rate-limit");
        Verification verification = new Verification(
                verNode.intValue("code-length", 6),
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
        if (verification.codeLength() < 4) {
            throw new ConfigException("verification.code-length", "至少为 4");
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

        ConfigNode botNode = root.section("bot");
        ConfigNode pairingNode = botNode.section("pairing");
        ConfigNode queryNode = botNode.section("query");
        int maxResults = queryNode.intValue("max-results", 50);
        if (maxResults < 1) {
            throw new ConfigException("bot.query.max-results", "必须为正数");
        }
        ConfigNode adminNode = botNode.section("admin");
        int maxPageSize = adminNode.intValue("max-page-size", 100);
        if (maxPageSize < 1) {
            throw new ConfigException("bot.admin.max-page-size", "必须为正数");
        }
        int adminRateLimit = adminNode.intValue("rate-limit-per-minute", 120);
        if (adminRateLimit < 1) {
            throw new ConfigException("bot.admin.rate-limit-per-minute", "必须为正数");
        }
        Bot bot = new Bot(
                botNode.bool("enabled", false),
                botNode.string("listen", "0.0.0.0"),
                botNode.intValue("port", 8765),
                new Pairing(
                        pairingNode.intValue("ttl-seconds", 600),
                        pairingNode.intValue("max-attempts", 5),
                        pairingNode.string("bootstrap-code", "")),
                new Query(
                        queryNode.bool("enabled", false),
                        maxResults),
                new Admin(
                        adminNode.bool("enabled", false),
                        List.copyOf(adminNode.stringListOrScalar("clients", List.of())),
                        adminNode.bool("read-only", false),
                        maxPageSize,
                        adminRateLimit));

        ConfigNode proxyNode = root.section("proxy");
        Proxy proxy = new Proxy(
                proxyNode.string("address", ""),
                proxyNode.intValue("port", 0));

        ConfigNode secNode = root.section("security");
        Security security = new Security(
                secNode.intValue("handshake-timeout-seconds", 10),
                secNode.intValue("clock-skew-seconds", 120));

        return new VelocityAuthConfig(language, database, servers, auth, registration, binding, verification,
                smtp, bot, proxy, security);
    }
}
