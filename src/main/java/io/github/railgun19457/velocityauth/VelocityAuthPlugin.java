package io.github.railgun19457.velocityauth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.auth.AccountCache;
import io.github.railgun19457.velocityauth.auth.AuthService;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.auth.PendingBindingStore;
import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.auth.ReconnectService;
import io.github.railgun19457.velocityauth.command.CommandRegistrar;
import io.github.railgun19457.velocityauth.config.ConfigService;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.listener.CommandGuardListener;
import io.github.railgun19457.velocityauth.listener.LoginListener;
import io.github.railgun19457.velocityauth.listener.PlayerLifecycleListener;
import io.github.railgun19457.velocityauth.listener.RoutingListener;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

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
    private StorageService storage;
    private AsyncExecutor async;
    private AuthSessionRegistry sessionRegistry;
    private AuthService authService;
    private ReconnectService reconnectService;

    private volatile boolean enabled;

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

            this.storage = StorageService.open(config.database(), dataDirectory, logger);
            this.async = new AsyncExecutor();

            AccountCache cache = new AccountCache(storage.accounts(), storage.identities(), log);
            cache.load();

            PasswordHasher hasher = new PasswordHasher(config.auth().bcryptCost());
            PendingBindingStore pendingBindings = new PendingBindingStore();
            RateLimiter rateLimiter = new RateLimiter();
            this.authService = new AuthService(this::config, storage, cache, hasher, pendingBindings,
                    rateLimiter, async, log);
            this.reconnectService = new ReconnectService(server, this::config, log);
            this.sessionRegistry = new AuthSessionRegistry();

            registerListeners();
            new CommandRegistrar(this, sessionRegistry).registerPlayerCommands();

            server.getScheduler()
                    .buildTask(this, () -> authService.purgeExpired())
                    .repeat(Duration.ofSeconds(60))
                    .schedule();

            this.enabled = true;
            log.info("VelocityAuth {} 已加载（语言 {}，数据库 {}，账号 {}）",
                    BuildConstants.VERSION, langService.code(), storage.describe(), cache.size());
            log.info("服务器映射：登录服={} 主服={} 记忆上次子服={}",
                    config.servers().login(), config.servers().defaultServer(), config.servers().rememberLast());
        } catch (IOException e) {
            log.error("启动失败：无法读取配置或语言文件", e);
        } catch (RuntimeException e) {
            log.error("启动失败：" + e.getMessage(), e);
        } catch (java.sql.SQLException e) {
            log.error("启动失败：数据库不可用（" + e.getMessage() + "）", e);
        }
    }

    private void registerListeners() {
        LoginListener loginListener = new LoginListener(this, sessionRegistry);
        RoutingListener routingListener = new RoutingListener(this, sessionRegistry);
        server.getEventManager().register(this, loginListener);
        server.getEventManager().register(this, routingListener);
        server.getEventManager().register(this, new CommandGuardListener(this, sessionRegistry));
        server.getEventManager().register(this, new PlayerLifecycleListener(this, sessionRegistry, routingListener));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.enabled = false;
        if (async != null) {
            async.close();
        }
        if (storage != null) {
            storage.close();
        }
        log.info("VelocityAuth 正在卸载…");
    }

    public boolean enabled() {
        return enabled;
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

    public StorageService storage() {
        return storage;
    }

    public LangService lang() {
        return langService;
    }

    public AsyncExecutor async() {
        return async;
    }

    public AuthService authService() {
        return authService;
    }

    public AuthSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    public ReconnectService reconnectService() {
        return reconnectService;
    }
}
