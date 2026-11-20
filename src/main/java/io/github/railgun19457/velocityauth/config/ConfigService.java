package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig.Domain;
import io.github.railgun19457.velocityauth.util.YamlUpgrade;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 配置加载 / 校验 / 热重载。
 * <p>
 * 首次启动释放模板，旧配置按键迁移，校验通过后才保存。
 */
public final class ConfigService {

    private static final String DEFAULT_RESOURCE = "/config.yml";

    private final Path configFile;
    private final Class<?> resourceAnchor;
    private final Logger logger;

    private volatile Loaded current;

    private record Loaded(VelocityAuthConfig config, List<String> warnings) {
    }

    public record Prepared(VelocityAuthConfig config, List<String> warnings, YamlUpgrade.Prepared file) {
    }

    public ConfigService(Path dataDirectory, Class<?> resourceAnchor, Logger logger) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.configFile = dataDirectory.resolve("config.yml");
        this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path configFile() {
        return configFile;
    }

    public VelocityAuthConfig config() {
        Loaded loaded = current;
        if (loaded == null) {
            throw new IllegalStateException("配置尚未加载");
        }
        return loaded.config();
    }

    public List<String> warnings() {
        Loaded loaded = current;
        return loaded == null ? List.of() : loaded.warnings();
    }

    /** 加载（或重载）配置，失败时抛出 {@link ConfigException} 并保留旧配置。 */
    public synchronized VelocityAuthConfig load() throws IOException {
        Prepared prepared = prepare();
        prepared.file().save(logger);
        publish(prepared);
        return prepared.config();
    }

    public Prepared prepare() throws IOException {
        YamlUpgrade.Prepared file;
        try {
            file = YamlUpgrade.prepare(resourceAnchor, DEFAULT_RESOURCE, configFile, ConfigService::upgradeKeys);
        } catch (YAMLException e) {
            throw new ConfigException("", "YAML 解析失败：" + e.getMessage(), e);
        }
        VelocityAuthConfig parsed = VelocityAuthConfig.from(ConfigNode.root(readYaml(file.content())));
        return new Prepared(parsed, List.copyOf(validate(parsed)), file);
    }

    public void publish(Prepared prepared) {
        current = new Loaded(prepared.config(), prepared.warnings());
        for (String warning : prepared.warnings()) {
            logger.warn("配置提示：{}", warning);
        }
    }

    private static void upgradeKeys(int version, YamlUpgrade.Editor keys) {
        boolean legacyQq = keys.has("binding.qq") || (!keys.has("binding.social")
                && ((version >= 0 && version < 3) || keys.has("qqbot") || keys.has("verification.qq-code-length")));
        String qqKey = keys.keyIgnoringCase("binding.social", "qq");
        String qq = "binding.social." + qqKey;
        keys.move("qqbot", "bot");
        keys.move("binding.qq", qq);
        keys.move(qq + ".max-accounts-per-qq", qq + ".max-accounts");
        keys.move("binding.email.max-accounts-per-email", "binding.email.max-accounts");
        keys.move("verification.email-code-length", "verification.code-length");
        if (legacyQq || keys.has(qq)) {
            keys.move("verification.qq-code-length", qq + ".code-length");
        } else {
            keys.remove("verification.qq-code-length");
        }
        keys.remove("auth.premium");
        keys.remove("bot.group-id");
        keys.remove("bot.private-number");
        keys.remove("bot.require-group-member");
        keys.fill(path -> switch (path) {
            // 现有配置缺少这些键时的运行默认值，与新装模板的示例值不同。
            case "binding.email.domain.list" -> false;
            case "binding.social", "binding.social.qq", "binding.social.qq.display-name",
                 "binding.social.qq.code-length" -> legacyQq && qqKey.equals("qq");
            default -> true;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(String content) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            Object loaded = yaml.load(new StringReader(content));
            if (loaded == null) {
                return new LinkedHashMap<>();
            }
            if (!(loaded instanceof Map)) {
                throw new ConfigException("", "配置根节点必须是映射（key: value）");
            }
            return (Map<String, Object>) loaded;
        } catch (YAMLException e) {
            throw new ConfigException("", "YAML 解析失败：" + e.getMessage(), e);
        }
    }

    /** 跨字段一致性检查；返回非致命警告。 */
    private List<String> validate(VelocityAuthConfig cfg) {
        List<String> out = new ArrayList<>();
        if (cfg.servers().login().isBlank()) {
            throw new ConfigException("servers.login", "不能为空");
        }
        if (cfg.servers().defaultServer().isBlank()) {
            throw new ConfigException("servers.default", "不能为空");
        }
        if (cfg.servers().login().equals(cfg.servers().defaultServer())) {
            out.add("servers.login 与 servers.default 相同（" + cfg.servers().login()
                    + "），未认证玩家与已认证玩家将进入同一服务器，隔离将失效");
        }
        for (String providerId : cfg.registration().requireBinding()) {
            java.util.Optional<Provider> provider = Provider.find(cfg, providerId);
            if (provider.isEmpty() || !provider.get().enabled()) {
                out.add("registration.require-binding 含 " + providerId + "，但该渠道不存在或未启用");
                continue;
            }
            if (provider.get().email()) {
                if (!cfg.smtp().enabled()) {
                    out.add("registration.require-binding 含 email，但 smtp.enabled=false，验证码邮件将无法发送");
                }
            } else if (!cfg.bot().enabled()) {
                out.add("registration.require-binding 含 " + providerId + "，但 bot.enabled=false，该渠道绑定将不可用");
            }
        }
        if (cfg.smtp().enabled() && cfg.smtp().username().isBlank()) {
            out.add("smtp.enabled=true 但 smtp.username 为空，邮件将无法发送");
        }
        if (cfg.smtp().enabled() && cfg.smtp().password().isBlank()) {
            out.add("smtp.enabled=true 但 smtp.password 为空（QQ 邮箱需填授权码）");
        }
        if (cfg.binding().email().domain().mode() != Domain.Mode.OFF
                && cfg.binding().email().domain().list().isEmpty()) {
            out.add("binding.email.domain.mode=" + cfg.binding().email().domain().mode().name().toLowerCase(Locale.ROOT)
                    + " 但 list 为空，所有邮箱域名都将" + (cfg.binding().email().domain().mode() == Domain.Mode.WHITELIST
                    ? "被拒绝" : "被放行"));
        }
        if (cfg.auth().session().enabled() && cfg.auth().session().ttlMinutes() <= 0) {
            throw new ConfigException("auth.session.ttl-minutes", "必须为正数");
        }
        int bindingHistoryDays = cfg.database().bindingHistoryDays();
        if (bindingHistoryDays > 0) {
            long maxCooldownMinutes = bindingHistoryDays * 1440L;
            for (Provider provider : Provider.all(cfg)) {
                if (provider.cooldownMinutes() > maxCooldownMinutes) {
                    out.add("binding." + provider.id() + ".cooldown-minutes 超过 database.binding-history-days"
                            + " 的覆盖范围，冷却可能因历史被清理而提前结束");
                }
            }
        }
        return out;
    }
}
