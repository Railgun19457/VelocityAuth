package io.github.railgun19457.velocityauth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.admin.AdminService;
import io.github.railgun19457.velocityauth.auth.AccountCache;
import io.github.railgun19457.velocityauth.auth.AuthService;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.BindingIndex;
import io.github.railgun19457.velocityauth.auth.BindingService;
import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.auth.PendingBindingStore;
import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.auth.ReconnectService;
import io.github.railgun19457.velocityauth.auth.RecoveryService;
import io.github.railgun19457.velocityauth.bot.AccountAdminHandler;
import io.github.railgun19457.velocityauth.bot.BotAdminHandler;
import io.github.railgun19457.velocityauth.bot.BotQueryHandler;
import io.github.railgun19457.velocityauth.bot.BotSocialGateway;
import io.github.railgun19457.velocityauth.bot.AccountQueryHandler;
import io.github.railgun19457.velocityauth.bot.BotServer;
import io.github.railgun19457.velocityauth.bot.BotSession;
import io.github.railgun19457.velocityauth.bot.KeyStore;
import io.github.railgun19457.velocityauth.auth.IdentityResolver;
import io.github.railgun19457.velocityauth.platform.FloodgateHook;
import io.github.railgun19457.velocityauth.bot.PairingService;
import io.github.railgun19457.velocityauth.query.AccountQueryService;
import io.github.railgun19457.velocityauth.mail.EmailTemplateService;
import io.github.railgun19457.velocityauth.mail.SmtpSender;
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
import io.github.railgun19457.velocityauth.verify.SocialVerificationGateway;
import io.github.railgun19457.velocityauth.verify.VerificationService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * VelocityAuth 主类：负责生命周期与组件装配。
 */
@Plugin(
        id = BuildConstants.ID,
        name = BuildConstants.NAME,
        version = BuildConstants.VERSION,
        description = "Account + password authentication with optional email/social verification and recovery.",
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
    private VerificationService verificationService;
    private BindingService bindingService;
    private EmailTemplateService emailTemplate;
    private RecoveryService recoveryService;
    private KeyStore keyStore;
    private PairingService pairingService;
    private BotServer botServer;
    private FloodgateHook floodgate;
    private IdentityResolver identityResolver;
    private AccountQueryService queryService;
    private io.github.railgun19457.velocityauth.admin.AdminService adminService;

    private RoutingListener routingListener;

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
            LangService.releaseShipped(getClass(), dataDirectory, logger);
            this.langService = LangService.load(config.language(), getClass(), dataDirectory, logger);

            this.storage = StorageService.open(config.database(), dataDirectory, logger);
            this.async = new AsyncExecutor();
            this.floodgate = FloodgateHook.create(server, log);
            this.identityResolver = new IdentityResolver(floodgate);

            AccountCache cache = new AccountCache(storage.accounts(), storage.identities(), log);
            cache.load();
            BindingIndex bindingIndex = new BindingIndex(storage.bindings(), log);
            bindingIndex.load();
            this.queryService = new AccountQueryService(storage, cache);

            PasswordHasher hasher = new PasswordHasher(config.auth().bcryptCost());
            PendingBindingStore pendingBindings = new PendingBindingStore();
            RateLimiter rateLimiter = new RateLimiter();
            this.authService = new AuthService(this::config, storage, cache, bindingIndex, hasher, pendingBindings,
                    rateLimiter, floodgate, async, log);
            this.reconnectService = new ReconnectService(server, this, this::config, log);
            this.sessionRegistry = new AuthSessionRegistry();

            SmtpSender smtpSender = new SmtpSender(this::config, async, log);
            this.emailTemplate = new EmailTemplateService(dataDirectory, getClass(), logger);
            if (emailTemplate.releaseDefaultIfMissing()) {
                log.info("已释放默认邮件模板到 {}", emailTemplate.file());
            }
            emailTemplate.load();
            this.keyStore = KeyStore.load(dataDirectory, log);
            this.pairingService = new PairingService();
            SocialVerificationGateway socialGateway = new BotSocialGateway(this::config, () -> botServer);
            this.verificationService = new VerificationService(this::config, this::lang, this::serverName,
                    smtpSender, emailTemplate, socialGateway, rateLimiter,
                    (task, delay) -> server.getScheduler().buildTask(this, task).delay(delay).schedule(),
                    async, log);
            this.bindingService = new BindingService(this::config, storage, bindingIndex, async, log);
            this.adminService = new AdminService(this::config, storage, authService, bindingService,
                    sessionRegistry, task -> server.getScheduler().buildTask(this, task).schedule(), log);
            startBotServer();
            this.recoveryService = new RecoveryService(storage, verificationService, async);

            registerListeners();
            new CommandRegistrar(this, sessionRegistry).registerPlayerCommands();

            server.getScheduler()
                    .buildTask(this, () -> {
                        authService.purgeExpired();
                        verificationService.purgeExpired();
                        recoveryService.purgeExpired();
                    })
                    .repeat(Duration.ofSeconds(60))
                    .schedule();

            this.enabled = true;
            log.info("VelocityAuth {} 已加载（语言 {}，数据库 {}，账号 {}）",
                    BuildConstants.VERSION, langService.code(), storage.describe(), cache.size());
            log.info("服务器映射：登录服={} 主服={} 记忆上次子服={}",
                    config.servers().login(), config.servers().defaultServer(), config.servers().rememberLast());
        } catch (IOException e) {
            log.error("启动失败：无法读取配置或语言文件", e);
            shutdownComponents();
        } catch (RuntimeException e) {
            log.error("启动失败：" + e.getMessage(), e);
            shutdownComponents();
        } catch (java.sql.SQLException e) {
            log.error("启动失败：数据库不可用（" + e.getMessage() + "）", e);
            shutdownComponents();
        }
    }

    private void registerListeners() {
        LoginListener loginListener = new LoginListener(this, sessionRegistry);
        this.routingListener = new RoutingListener(this, sessionRegistry);
        server.getEventManager().register(this, loginListener);
        server.getEventManager().register(this, routingListener);
        server.getEventManager().register(this, new CommandGuardListener(this, sessionRegistry));
        server.getEventManager().register(this, new PlayerLifecycleListener(this, sessionRegistry, routingListener));
    }

    private void startBotServer() {
        VelocityAuthConfig.Bot settings = config().bot();
        if (!settings.enabled()) {
            log.info("bot 通道未启用（bot.enabled=false）");
            return;
        }
        // 无控制台部署：可用 config.yml 的 bootstrap-code 预置一次配对
        String bootstrapCode = settings.pairing().bootstrapCode();
        if (bootstrapCode != null && !bootstrapCode.isBlank()) {
            PairingService.Created created = pairingService.createWithCode(bootstrapCode,
                    settings.pairing().ttlSeconds(), settings.pairing().maxAttempts());
            // 只记录 pairing_id，绝不记录配对码本身
            log.info("已按配置预置配对（bootstrap）：pairing_id={}，TTL {} 秒",
                    created.pairingId(), created.ttlSeconds());
        }
        BotQueryHandler queryHandler = settings.query().enabled()
                ? new AccountQueryHandler(queryService, storage, this::config,
                        () -> sessionRegistry == null ? 0 : sessionRegistry.authenticatedSessions().size(),
                        () -> botServer == null ? 0 : botServer.readyCount(),
                        () -> adminService == null ? java.util.Set.of() : adminService.onlineAccountIds(),
                        this::onlineSessions)
                : null;
        BotAdminHandler adminHandler = settings.admin().enabled() && adminService != null
                ? new AccountAdminHandler(adminService, this::config, log)
                : null;
        this.botServer = new BotServer(settings, keyStore, pairingService, this::config, log,
                new BotServer.Handler() {
                    @Override
                    public boolean onVerifyMatched(String providerId, String code, String subject, String scope,
                                                   String displayName) {
                        return verificationService.onSocialMatched(providerId, code, subject, scope, displayName);
                    }

                    @Override
                    public void onSessionReady(BotSession session) {
                        log.info("bot 会话就绪：client_id={} provider={}", session.clientId(), session.provider());
                    }
                }, queryHandler, adminHandler);
        try {
            this.botServer.start();
        } catch (RuntimeException e) {
            log.error("启动 bot 通道失败：" + e.getMessage(), e);
            this.botServer = null;
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.enabled = false;
        shutdownComponents();
        log.info("VelocityAuth 正在卸载…");
    }

    /** 释放组件（启动失败与正常关服共用；可重复调用）。 */
    private void shutdownComponents() {
        KeyStore keys = keyStore;
        if (keys != null) {
            keyStore = null;
            keys.flush();
        }
        BotServer bot = botServer;
        if (bot != null) {
            botServer = null;
            bot.shutdown();
        }
        AsyncExecutor executor = async;
        if (executor != null) {
            async = null;
            executor.close();
        }
        StorageService store = storage;
        if (store != null) {
            storage = null;
            store.close();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 热重载配置、语言文件与邮件模板；失败时抛出，调用方负责提示。 */
    public void reload() throws IOException {
        VelocityAuthConfig config = configService.load();
        LangService.releaseShipped(getClass(), dataDirectory, logger);
        this.langService = LangService.load(config.language(), getClass(), dataDirectory, logger);
        emailTemplate.load();
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

    public VerificationService verifications() {
        return verificationService;
    }

    public BindingService bindings() {
        return bindingService;
    }

    public RecoveryService recovery() {
        return recoveryService;
    }

    public KeyStore keyStore() {
        return keyStore;
    }

    public PairingService pairings() {
        return pairingService;
    }

    public BotServer botServer() {
        return botServer;
    }

    public FloodgateHook floodgate() {
        return floodgate;
    }

    public IdentityResolver identities() {
        return identityResolver;
    }

    /** 玩家信息查询服务（bot 查询接口与 /vauth account info 共用）。 */
    public AccountQueryService queries() {
        return queryService;
    }

    /** 账号管理写操作（命令与 bot admin.* 共用）。 */
    public io.github.railgun19457.velocityauth.admin.AdminService admin() {
        return adminService;
    }

    /** 在线已认证会话（供 bot 管理页列表）。 */
    private List<AccountQueryHandler.OnlineSession> onlineSessions() {
        List<AccountQueryHandler.OnlineSession> out = new java.util.ArrayList<>();
        if (sessionRegistry == null) {
            return out;
        }
        for (AuthSession session : sessionRegistry.authenticatedSessions()) {
            out.add(new AccountQueryHandler.OnlineSession(
                    session.account() == null ? 0L : session.account().id(),
                    session.account() == null ? "" : session.account().name(),
                    session.connectionName(),
                    session.ip(),
                    session.authenticatedAt() / 1000L));
        }
        return out;
    }

    /** 路由监听器（已认证玩家的目标子服选择）。 */
    public RoutingListener routing() {
        return routingListener;
    }

    /** 服务器名（用于邮件主题），取代理 MOTD 的纯文本。 */
    public String serverName() {
        try {
            String plain = PlainTextComponentSerializer.plainText()
                    .serialize(server.getConfiguration().getMotd()).trim();
            return plain.isEmpty() ? BuildConstants.NAME : plain;
        } catch (RuntimeException e) {
            return BuildConstants.NAME;
        }
    }
}
