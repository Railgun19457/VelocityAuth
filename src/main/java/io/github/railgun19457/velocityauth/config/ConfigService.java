package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig.Domain;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig.Registration;
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
import java.util.Map;
import java.util.Objects;

/**
 * 配置加载 / 校验 / 热重载。
 * <p>
 * 首次启动时从 jar 内释放默认模板 {@code config.yml} 到数据目录；已存在则不覆盖。
 */
public final class ConfigService {

    private static final String DEFAULT_RESOURCE = "/config.yml";

    private final Path dataDirectory;
    private final Path configFile;
    private final Class<?> resourceAnchor;
    private final Logger logger;

    private volatile VelocityAuthConfig config;
    private volatile List<String> warnings = List.of();

    public ConfigService(Path dataDirectory, Class<?> resourceAnchor, Logger logger) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
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

    /** 首次启动释放默认模板；返回是否新建。 */
    public boolean releaseDefaultIfMissing() throws IOException {
        if (Files.exists(configFile)) {
            return false;
        }
        Files.createDirectories(dataDirectory);
        try (InputStream in = resourceAnchor.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("jar 内缺少默认配置模板 " + DEFAULT_RESOURCE);
            }
            Files.copy(in, configFile);
        }
        return true;
    }

    /** 加载（或重载）配置，失败时抛出 {@link ConfigException} 并保留旧配置。 */
    public VelocityAuthConfig load() throws IOException {
        if (!Files.exists(configFile)) {
            releaseDefaultIfMissing();
        }
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
        if (cfg.registration().gate() == Registration.Gate.EMAIL && !cfg.binding().email().enabled()) {
            out.add("registration.gate=email 但 binding.email.enabled=false，邮箱验证将不可用");
        }
        if (cfg.registration().gate() == Registration.Gate.QQ && !cfg.qqbot().enabled()) {
            out.add("registration.gate=qq 但 qqbot.enabled=false，QQ 验证将不可用");
        }
        if (cfg.smtp().enabled() && cfg.smtp().username().isBlank()) {
            out.add("smtp.enabled=true 但 smtp.username 为空，邮件将无法发送");
        }
        if (cfg.smtp().enabled() && cfg.smtp().password().isBlank()) {
            out.add("smtp.enabled=true 但 smtp.password 为空（QQ 邮箱需填授权码）");
        }
        if (cfg.binding().email().domain().mode() != Domain.Mode.OFF
                && cfg.binding().email().domain().list().isEmpty()) {
            out.add("binding.email.domain.mode=" + cfg.binding().email().domain().mode().name().toLowerCase()
                    + " 但 list 为空，所有邮箱域名都将" + (cfg.binding().email().domain().mode() == Domain.Mode.WHITELIST
                    ? "被拒绝" : "被放行"));
        }
        if (cfg.qqbot().enabled() && cfg.qqbot().groupId().isBlank()) {
            out.add("qqbot.enabled=true 但 qqbot.group-id 为空，QQ 验证无法工作");
        }
        if (cfg.auth().session().enabled() && cfg.auth().session().ttlMinutes() <= 0) {
            throw new ConfigException("auth.session.ttl-minutes", "必须为正数");
        }
        return out;
    }
}
