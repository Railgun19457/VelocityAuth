package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig.Domain;
import io.github.railgun19457.velocityauth.util.ReleasedResource;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * 首次启动时从 jar 内释放默认模板 {@code config.yml} 到数据目录；已存在则不覆盖。
 */
public final class ConfigService {

    private static final String DEFAULT_RESOURCE = "/config.yml";

    private final Path configFile;
    private final Class<?> resourceAnchor;
    private final Logger logger;

    private volatile VelocityAuthConfig config;
    private volatile List<String> warnings = List.of();

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
        VelocityAuthConfig current = config;
        if (current == null) {
            throw new IllegalStateException("配置尚未加载");
        }
        return current;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** 首次启动释放默认模板，并在内置版本更新时释放/升级；返回是否新建。 */
    public boolean releaseDefaultIfMissing() {
        ReleasedResource.Outcome outcome = ReleasedResource.release(
                resourceAnchor, DEFAULT_RESOURCE, configFile, false, logger);
        switch (outcome) {
            case CREATED -> logger.info("[VelocityAuth] 已释放默认配置到 {}", configFile);
            case NEW_AVAILABLE -> logger.warn("[VelocityAuth] 内置配置模板已更新，新模板已写出到 {}.new，"
                    + "请自行比对合并（不会自动覆盖你的配置，避免冲掉 SMTP 授权码等凭据）", configFile);
            default -> {
            }
        }
        return outcome == ReleasedResource.Outcome.CREATED;
    }

    /** 加载（或重载）配置，失败时抛出 {@link ConfigException} 并保留旧配置。 */
    public VelocityAuthConfig load() throws IOException {
        releaseDefaultIfMissing();
        Map<String, Object> root = readYaml(configFile);
        VelocityAuthConfig parsed = VelocityAuthConfig.from(ConfigNode.root(root));
        List<String> collected = validate(parsed);
        this.config = parsed;
        this.warnings = List.copyOf(collected);
        for (String warning : collected) {
            logger.warn("配置提示：{}", warning);
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
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
