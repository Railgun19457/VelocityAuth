package io.github.railgun19457.velocityauth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.config.ConfigService;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * VelocityAuth 主类：负责生命周期与组件装配。
 */
@Plugin(
        id = BuildConstants.ID,
        name = BuildConstants.NAME,
        version = BuildConstants.VERSION,
        description = "Account + password authentication with optional email/QQ verification and recovery.",
        authors = {"Railgun19457"},
        dependencies = {
                @Dependency(id = "floodgate", optional = true)
        }
)
public final class VelocityAuthPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final PluginLogger log;

    private ConfigService configService;
    private LangService langService;

    @Inject
    public VelocityAuthPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.log = new PluginLogger(logger);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.configService = new ConfigService(dataDirectory, getClass(), logger);
            boolean created = configService.releaseDefaultIfMissing();
            if (created) {
                log.info("已释放默认配置到 {}", configService.configFile());
            }
            VelocityAuthConfig config = configService.load();
            this.langService = LangService.load(config.language(), getClass(), dataDirectory, logger);

            log.info("VelocityAuth {} 已加载（语言 {}，数据库 {}）",
                    BuildConstants.VERSION, langService.code(), config.database().type().name().toLowerCase());
            log.info("服务器映射：登录服={} 主服={} 记忆上次子服={}",
                    config.servers().login(), config.servers().defaultServer(), config.servers().rememberLast());
        } catch (IOException e) {
            log.error("启动失败：无法读取配置或语言文件", e);
        } catch (RuntimeException e) {
            log.error("启动失败：" + e.getMessage(), e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        log.info("VelocityAuth 正在卸载…");
    }

    /** 热重载配置与语言文件；失败时抛出，调用方负责提示。 */
    public void reload() throws IOException {
        VelocityAuthConfig config = configService.load();
        this.langService = LangService.load(config.language(), getClass(), dataDirectory, logger);
    }

    public ProxyServer server() {
        return server;
    }

    public PluginLogger log() {
        return log;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public ConfigService configService() {
        return configService;
    }

    public VelocityAuthConfig config() {
        return configService.config();
    }

    public LangService lang() {
        return langService;
    }
}
