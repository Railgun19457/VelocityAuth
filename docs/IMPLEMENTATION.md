# VelocityAuth 开发须知

> 面向插件开发者的工程手册：技术选型、工程结构、Velocity API 参考、实现坑、测试策略、决策记录
>
> 配套文档：[`DESIGN.md`](DESIGN.md)（设计）· [`PROTOCOL.md`](PROTOCOL.md)（bot 协议契约）

---

## 目录

1. [技术栈与版本](#1-技术栈与版本)
2. [工程结构与包布局](#2-工程结构与包布局)
3. [依赖清单](#3-依赖清单)
4. [Velocity API 参考](#4-velocity-api-参考)
5. [实现要点与坑](#5-实现要点与坑)
6. [测试策略](#6-测试策略)
7. [编码约定](#7-编码约定)
8. [决策记录（ADR）](#8-决策记录adr)

---

## 1. 技术栈与版本

| 项 | 选择 | 说明 |
|---|---|---|
| JDK | **21** | 与 Serveroute 一致；`release = 21` |
| 构建 | **Gradle** + `com.gradleup.shadow` | 打包为 fat jar，对齐 Serveroute |
| 代理 API | **`com.velocitypowered:velocity-api:3.5.1`** | `compileOnly` + `annotationProcessor` |
| 配置 | **YAML**（SnakeYAML，shade + relocate） | 见第 3 节 |
| 数据库 | **SQLite**（默认）/ **MySQL** | JDBC + HikariCP |
| 密码 | **bcrypt** | `at.favre.lib:bcrypt` |
| 邮件 | **Jakarta Mail**（`angus-mail`） | 内置 SMTP |
| WS 服务端 | **Java-WebSocket** | 轻量，避开与 Velocity 内置 Netty 的冲突 |
| JSON | **Gson** | 与 Velocity 内置版本兼容；必要时 shade |
| 加密 | **JDK 内置** | Ed25519 / X25519 / AES-GCM / HMAC-SHA256 / SHA-256，**无需 BouncyCastle** |
| 基岩身份 | **Floodgate API**（可选，`compileOnly`） | 未安装时功能降级 |
| 测试 | **JUnit 5** | 纯逻辑单测 + 协议向量测试 |

> ⚠️ 上述第三方库版本以**实际实现时的最新稳定版**为准，本文件只锁定大方向。

---

## 2. 工程结构与包布局

```
VelocityAuth/
  build.gradle
  settings.gradle
  gradle.properties
  .gitignore
  config.example.yml
  docs/
    DESIGN.md
    PROTOCOL.md
    IMPLEMENTATION.md
    protocol/
      tools/gen_vectors.py
      vectors/*.json
  src/main/java/io/github/railgun19457/velocityauth/
    VelocityAuthPlugin.java          # 主类：@Plugin、生命周期、装配
    BuildConstants.java              # 版本常量
    config/
      ConfigService.java             # 加载/校验/热重载
      VelocityAuthConfig.java        # 配置模型
    lang/
      LangService.java               # i18n 加载与格式化
    storage/
      Database.java                  # 连接池、方言
      Schema.java                    # 建表与迁移
      AccountRepository.java
      IdentityRepository.java
      BindingRepository.java
      SessionRepository.java
      AuditRepository.java
      model/                         # Account / Identity / Binding / ...
    auth/
      AuthService.java               # 注册/登录/改密/找回编排
      PasswordHasher.java            # bcrypt 封装
      IdentityResolver.java          # 连接名/基岩身份 → 账号
      ProfileFactory.java            # 账号 → GameProfile
      PendingBindingStore.java       # 重连绑定（内存）
      SessionStore.java              # 会话保持（可选）
      RateLimiter.java
    verify/
      VerificationSession.java
      VerificationService.java       # 统一验证会话
      EmailChannel.java              # SMTP
      QqChannel.java                 # 走 bot
      VerificationPurpose.java
      VerificationChannel.java
    mail/
      SmtpSender.java
    bot/
      BotServer.java                 # WS 服务端
      BotSession.java                # 单连接会话状态
      protocol/                      # 帧、规范化、签名、加密（对齐 PROTOCOL.md）
        Frame.java
        Canonical.java
        SignatureInput.java
        KeySchedule.java
        Sealer.java
        HandshakeHandler.java
      PairingService.java
      KeyStore.java                  # keys/identity.json, paired_clients.json
    listener/
      LoginListener.java             # PreLogin/Login/GameProfileRequest
      RoutingListener.java           # PlayerChooseInitialServer/ServerPreConnect/ServerPostConnect
      CommandGuardListener.java      # CommandExecuteEvent
      PlayerLifecycleListener.java   # PostLogin/Disconnect（记忆子服、审计）
    command/
      LoginCommand.java
      RegisterCommand.java
      ChangePasswordCommand.java
      LogoutCommand.java
      BindCommand.java
      UnbindCommand.java
      ForgotCommand.java
      AdminCommand.java
    platform/
      FloodgateHook.java             # 可选依赖，反射/软依赖
    util/
      PluginLogger.java
      MessageRenderer.java           # MiniMessage + 点击复制
  src/main/resources/
    velocity-plugin.json
    config.yml                       # 默认模板（占位符、开关默认关）
    lang/zh_cn.yml
    lang/en_us.yml
  src/test/java/...
```

---

## 3. 依赖清单

`build.gradle`（Groovy，对齐 Serveroute 风格）：

```groovy
plugins {
    id 'java'
    id 'com.gradleup.shadow' version '8.3.6'
}

group = 'io.github.railgun19457'
version = '0.1.0-SNAPSHOT'

repositories {
    mavenCentral()
    maven { url = 'https://repo.papermc.io/repository/maven-public/' }
    maven { url = 'https://repo.opencollab.dev/main/' }   // Floodgate
}

dependencies {
    // 代理 API
    compileOnly 'com.velocitypowered:velocity-api:3.5.1'
    annotationProcessor 'com.velocitypowered:velocity-api:3.5.1'

    // 基岩身份（可选）
    compileOnly 'org.geysermc.floodgate:api:2.2.4-SNAPSHOT'

    // 运行时依赖（shade + relocate）
    implementation 'org.yaml:snakeyaml:2.2'
    implementation 'com.google.code.gson:gson:2.11.0'
    implementation 'at.favre.lib:bcrypt:0.10.2'
    implementation 'com.zaxxer:HikariCP:5.1.0'
    implementation 'org.xerial:sqlite-jdbc:3.46.1.3'
    implementation 'com.mysql:mysql-connector-j:8.4.0'
    implementation 'org.eclipse.angus:angus-mail:2.0.3'
    implementation 'org.java-websocket:Java-WebSocket:1.5.7'

    // 测试
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'com.velocitypowered:velocity-api:3.5.1'
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 21
}

tasks.named('test') { useJUnitPlatform() }

tasks.named('jar') { archiveClassifier.set('plain') }

shadowJar {
    archiveClassifier.set('')
    mergeServiceFiles()
    relocate 'org.yaml.snakeyaml', 'io.github.railgun19457.velocityauth.lib.snakeyaml'
    relocate 'com.google.gson',     'io.github.railgun19457.velocityauth.lib.gson'
    relocate 'at.favre.lib',        'io.github.railgun19457.velocityauth.lib.bcrypt'
    relocate 'com.zaxxer.hikari',   'io.github.railgun19457.velocityauth.lib.hikari'
    relocate 'org.java_websocket',  'io.github.railgun19457.velocityauth.lib.ws'
    relocate 'org.eclipse.angus',   'io.github.railgun19457.velocityauth.lib.mail'
    relocate 'jakarta.mail',        'io.github.railgun19457.velocityauth.lib.mailapi'
}

build.dependsOn shadowJar
```

`src/main/resources/velocity-plugin.json`：

```json
{
  "id": "velocityauth",
  "name": "VelocityAuth",
  "version": "${version}",
  "description": "Account + password authentication with optional email/QQ verification and recovery.",
  "authors": ["Railgun19457"],
  "dependencies": [],
  "main": "io.github.railgun19457.velocityauth.VelocityAuthPlugin"
}
```

> Floodgate 通过 `@Plugin(dependencies = @Dependency(id = "floodgate", optional = true))` 声明为**可选软依赖**，未安装时相关功能自动降级。

---

## 4. Velocity API 参考

### 4.1 生命周期与注入

```java
@Plugin(id = "velocityauth", name = "VelocityAuth", version = BuildConstants.VERSION,
        authors = {"Railgun19457"})
public final class VelocityAuthPlugin {
    @Inject
    public VelocityAuthPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) { ... }

    @Subscribe public void onInit(ProxyInitializeEvent event) { ... }
    @Subscribe public void onShutdown(ProxyShutdownEvent event) { ... }
}
```

### 4.2 事件清单（本插件用到的）

| 事件 | 用途 | 可取消 | 线程 |
|---|---|---|---|
| `ProxyInitializeEvent` / `ProxyShutdownEvent` | 启停 | — | — |
| `PreLoginEvent` | 最早拦截（封禁/名称占用） | ✅ | async |
| `GameProfileRequestEvent` | **profile 重写（核心）** | — | async |
| `LoginEvent` | 登录结果 | ✅ | async |
| `PostLoginEvent` | 玩家对象就绪 | — | async |
| `PlayerChooseInitialServerEvent` | **路由（登录服/记忆子服）** | ✅ | async |
| `ServerPreConnectEvent` | **未认证兜底拦截** | ✅ | async |
| `ServerPostConnectEvent` | **记录 last_server** | — | async |
| `CommandExecuteEvent` | **未认证命令拦截** | ✅ | async |
| `PlayerDisconnectEvent` | 清理/记录 | — | async |
| `PermissionsSetupEvent` | 权限函数 | — | async |

> ⚠️ `PlayerChatEvent` **不可取消**——这也是为什么未认证玩家必须完全隔离在登录服（见 `DESIGN.md` 4.4）。

### 4.3 关键 API

```java
// profile 重写（唯一能改 name/UUID 的公开 API）
event.setGameProfile(new GameProfile(uuid, name, properties));

// 规范 UUID
UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nameLower).getBytes(StandardCharsets.UTF_8));
// 等价于 GameProfile.forOfflinePlayer(nameLower)

// 重连（4.3 流程）
player.transferToHost(proxySelfAddress);       // 需客户端 1.20.5+，否则降级
player.disconnect(Component.text("登录成功，请重新连接"));

// 路由
event.setResult(PlayerChooseInitialServerEvent... ); // 或 setInitialServer

// 拦截跳服
event.setResult(ServerPreConnectEvent.ServerResult.denied());

// 命令注册
proxyServer.getCommandManager().register(
    proxyServer.getCommandManager().metaBuilder("login").plugin(this).build(),
    new LoginCommand(...));

// Adventure
Component msg = MiniMessage.miniMessage().deserialize(text);
msg = msg.clickEvent(ClickEvent.copyToClipboard(code));   // 点击复制（QQ 验证码）
player.sendMessage(msg);
```

### 4.4 代理自身地址

`transferToHost` 需要代理对外地址与端口。从配置读取（推荐显式配置 `proxy.address` / `proxy.port`），避免 NAT/多网卡下自动探测出错。

---

## 5. 实现要点与坑

### 5.1 profile 与身份

1. **`GameProfileRequestEvent` 只在登录握手阶段触发一次**，这是唯一的身份改写点；运行期改 name/UUID 需要反射内部字段，本设计不采用。
2. `Player#setGameProfileProperties()` **只能改属性**（皮肤等），改不了 name/UUID。
3. UUID 用**小写规范名**计算，保证大小写不敏感账号得到同一 UUID。
4. 重写后**客户端自己也会看到账号名**（因为重写在 `LoginSuccess` 之前），所以不会出现聊天签名不一致。

### 5.2 重连绑定

5. `pendingBinding` 的 key 用 `(连接名小写, IP)`，TTL 90 秒，**消费即删**。
6. `transferToHost` 需客户端支持 Transfer 包（1.20.5+）；老客户端降级为 `disconnect` + 提示重连。
7. 重连后必须由 `GameProfileRequestEvent` 命中 binding 才标记"已认证"，**不要**在命令执行时就直接改状态。

### 5.3 异步与线程

8. 事件多为 async；**DB / SMTP / 网络 IO 一律不得阻塞事件循环**，用独立线程池（虚拟线程可用）。
9. `PreLoginEvent` / `GameProfileRequestEvent` 是 `@AwaitingEvent`，代理会等待——**其中只做快速内存查表**，需要 DB 时提前缓存或用极短超时。
10. 内存态（pendingBinding、验证会话、限流）必须线程安全。

### 5.4 隔离与路由

11. 未认证隔离三件套：`PlayerChooseInitialServerEvent`（只去登录服）+ `CommandExecuteEvent`（拦命令）+ `ServerPreConnectEvent`（兜底拦跳服）。
12. `PlayerChatEvent` 不可取消 → 未认证玩家不能进任何后端主服。
13. 登录服**必须是专用服**，不要挂按 UUID 存数据的插件。

### 5.5 Geyser / Floodgate

14. Floodgate 也用 `GameProfileRequestEvent` 注入 profile，**监听器顺序存在竞争**，需实测；必要时在 `ServerPostConnectEvent` 二次校正。
15. 重写为 Java UUID 后基岩皮肤可能丢失；尝试携带 `textures` 属性，能否生效需实测。
16. Floodgate 为**可选依赖**：未安装时基岩相关分支直接跳过，不得抛错。

### 5.6 bot 通道

17. 严格按 [`PROTOCOL.md`](PROTOCOL.md) 实现；测试向量必须全绿。
18. AES-GCM 的 `nonce` **必须**由 `seq` 确定性派生，**严禁随机**；`seq` 每方向严格递增并拒绝重放。
19. 临时 X25519 密钥**每连接重新生成**（前向保密）。
20. 日志**绝不记录**配对码、验证码、明文密码。

### 5.7 配置与数据

21. 配置只提交模板；真实配置、`keys/`、`*.db` 一律 `.gitignore`。
22. 首次启动从 jar 释放默认 `config.yml`；已存在则不覆盖。
23. 配置校验失败要给出**明确的行号/字段提示**，并拒绝启动而非静默降级。

---

## 6. 测试策略

| 层级 | 内容 | 手段 |
|---|---|---|
| 单元测试 | 规范化 / 签名输入 / HKDF / 密封帧 | 对照 `protocol/vectors/*.json` |
| 单元测试 | 账号名规则、邮箱域名匹配、限流窗口、bcrypt | JUnit 5 |
| 集成测试 | 仓库层（SQLite 内存库） | JUnit 5 + 临时文件库 |
| 协议测试 | 本地起 `BotServer`，用测试客户端跑完整 pair + auth + verify 流程 | Java-WebSocket 测试客户端 |
| 手工验证 | 真实 Velocity + 登录服 + 主服，跑通注册/登录/重连/记忆子服 | 手工 |
| 手工验证 | Geyser + Floodgate 基岩端（顺序、皮肤） | 手工 |

**协议向量测试是硬性门槛**：bot 侧与插件侧都必须能通过 `protocol/vectors/`。

---

## 7. 编码约定

- 包名统一 `io.github.railgun19457.velocityauth.*`
- 依赖注入用 Guice（Velocity 原生），构造器注入，避免静态单例
- 面向玩家的文本**一律走 i18n**，不硬编码
- 日志用注入的 `Logger`（SLF4J），前缀统一
- 异常不外泄到事件循环；网络/DB 失败要降级并记录
- 代码风格对齐 Serveroute（同作者、同仓库风格）

---

## 8. 决策记录（ADR）

记录关键取舍，避免新会话重复讨论。

### ADR-1 身份改写走"登录后重连"而非反射

- **背景**：需要让任意游戏 ID 登录后表现为账号身份。
- **选项**：A) 重连/Transfer，在 `GameProfileRequestEvent` 改写；B) 反射改 `ConnectedPlayer.profile` 后切服。
- **决定**：**A**。
- **理由**：A 纯公开 API、跨版本稳定、客户端与服务端认知一致（无聊天签名问题）；B 依赖内部字段名、需关 `enforce-secure-profile`、会打乱 TAB 等按名字的功能。
- **代价**：登录后有一次重连；1.20.5+ 用 `transferToHost` 基本无感。

### ADR-2 账号 = 规范身份（name + offline UUID）

- **决定**：`uuid = nameUUIDFromBytes("OfflinePlayer:" + 小写账号名)`。
- **理由**：所有按 UUID 存数据的插件天然统一；账号名恰好等于旧游戏 ID 的玩家历史数据无缝保留。

### ADR-3 验证机制统一抽象

- **决定**：注册门槛 / 绑定 / 找回共用 `VerificationSession`（purpose × channel）。
- **理由**：TTL、限次、限流、审计只写一份；后续加场景零成本。

### ADR-4 QQ 验证在公开群发码、敏感操作在游戏内完成

- **决定**：游戏内显示码 → 群里发送 → bot 回报 → **游戏内**继续。
- **理由**：不要求玩家加 bot 好友；公开群里的码本身不敏感；真正的敏感操作（设/改密码）不出游戏。

### ADR-5 bot 通道自加密（X25519 + AES-GCM），不依赖 TLS

- **决定**：应用层 X25519 ECDH + HKDF + AES-256-GCM。
- **理由**：插件端口可能直接暴露；不引入反向代理；纯 JDK 可实现。
- **代价**：需自行处理 nonce 计数与重放（见 `PROTOCOL.md` 第 10 节）。

### ADR-6 配对码高熵，不采用 SRP-6a

- **决定**：配对码 ≥128 bit，直接以 Ed25519 签名互证（配对码进签名 transcript，不上线）。
- **理由**：SRP 的价值是保护**低熵**口令；高熵码不需要 PAKE。去掉后无 BouncyCastle 依赖，bot 侧也无需 Python `srp`。

### ADR-7 会话保持默认关闭

- **决定**：`auth.session.enabled: false`。
- **理由**：国内 NAT 共享 IP 普遍，同 IP 免密风险高；由服主按需开启。

### ADR-8 正版名冲突"先来后到"

- **决定**：`name-conflict: first-come`。
- **理由**：符合服主诉求；代价是正版玩家可能用不了自己的 Mojang 名，已确认接受。
- **备选**：`premium-priority`（需外部正版名单）。

### ADR-9 不提供"查看明文密码"

- **决定**：不实现；改以 `/vauth forcelogin` 满足运维顶号需求。
- **理由**：bcrypt 单向哈希，插件本就拿不到明文。

### ADR-10 bot 通道由插件作服务端

- **决定**：插件监听，bot 主动连接。
- **理由**：与 OneBot 反向 WS 习惯一致；bot 断线自动重连，运维简单。

### ADR-11 注册门槛开启时 `/register` 的邮箱参数位置

- **背景**：`DESIGN.md` 8.1 规定 `registration.gate: none | email | qq`，但 9.1 的
  `/register <账号> <密码> [确认密码]` 没有留出"收码邮箱"的位置；而 `gate=email` 必须先知道邮箱才能发码。
- **选项**：A) 额外参数 `gate=email` 时把邮箱放在第 3 位；B) 先 `/bind email` 再 `/register`（但 `/bind` 要求已认证，鸡生蛋问题）；C) 单独加 `/register-email` 命令。
- **决定**：**A**。
  - `gate=none` / `gate=qq`：`/register <账号> <密码> [确认密码]`
  - `gate=email`：`/register <账号> <密码> <邮箱> [确认密码]`
- **理由**：保持单条命令、参数自然、无需引入新的认证前状态；`gate=qq` 不需要邮箱，故不变。
- **代价**：`gate=email` 时命令签名与文档 9.1 有一处位置差异，已在本文档与 `/register` 用法提示中说明。

### ADR-12 验证完成回调必须切换线程

- **背景**：`VerificationService` 的完成回调（如"验证通过 → 建号 → 断开重连"）若在
  提交验证码的命令线程上**内联**执行，会在命令自身后续提示（"验证成功"）之前就把玩家断开，导致提示丢失。
- **决定**：`VerificationService.finish()` 使用 `CompletableFuture.completeAsync(..., executor)`，
  在插件的虚拟线程执行器上完成，保证命令线程先返回、提示先送达。
- **代价**：建号等落库操作在异步线程执行（本来就应如此）。

### ADR-13 QQ 渠道的验证码**不能**在游戏内提交

- **背景**：QQ 验证码会在公开群里出现，玩家的客户端也会显示它。若允许 `/verify <码>` 在游戏内直接完成
  QQ 渠道的会话，任何看到群消息的人（或玩家本人不经过 QQ）都能通过验证，**"证明持有该 QQ" 的语义完全失效**。
- **决定**：`VerificationService.submitCode()` 对 `channel = QQ` 的会话一律返回 `WRONG_CHANNEL`，
  不消耗尝试次数、不完成会话；QQ 渠道只能由 bot 的 `verify.matched`（群成员校验通过）完成。
- **影响**：`/verify <码>` 只服务于 email 渠道；QQ 渠道的提示文案改为"请在群里发送验证码"。

### ADR-14 无控制台部署的配对引导（`qqbot.pairing.bootstrap-code`）

- **背景**：配对码由 `/velocityauth pair new` 生成并只在控制台显示；但容器/无 TTY 环境下
  Velocity 的控制台**不读取重定向的 stdin**（已实测：命令无任何响应），服主无法执行该命令。
- **决定**：新增可选配置 `qqbot.pairing.bootstrap-code`。非空时插件在启动时用它创建一条**一次性**配对记录
  （TTL、限次与 `/pair new` 完全一致），日志只记录 `pairing_id`，**绝不记录配对码本身**。
- **理由**：不引入任何"测试专用"代码路径；配对码由服主自己写入配置文件，插件不打印它；
  记录仍只存内存、用后即弃。
- **代价**：配置里的码是长期静态值（不是随机生成），因此仅建议在无控制台环境下临时开启，
  配对完成后立即清空该字段（并撤销不再需要的 `client revoke`）。

### ADR-15 基岩端身份用 Floodgate UUID，且 profile 重写必须排在 Floodgate 之后

- **背景**：基岩玩家的连接名是 `.<gamertag>`（如 `.Steve`），若沿用 Java 的"连接名即身份"，
  所有基岩玩家会互相顶号；另外 Floodgate 自己也监听 `GameProfileRequestEvent` 注入 profile（R2）。
- **决定**：
  1. 身份解析按端分流：`IdentityResolver` 判断 UUID 是否为 Floodgate ID，基岩端身份标识取
     **Floodgate UUID**（写入 `identities.kind='bedrock'`），内存态 key 前缀 `bedrock:` / `java:` 互不碰撞。
  2. profile 重写监听器使用 `@Subscribe(order = PostOrder.LAST)`，确保在 Floodgate 之后执行、重写生效。
  3. 皮肤：重写时沿用 `original.getProperties()`，即把 Floodgate 注入的 `textures` 属性带过去（R3）。
  4. Floodgate 未安装时全部降级（`FloodgateHook` 内所有 API 调用都在 `try/catch(Throwable)` 内）。
- **未实测项（保留在 R2/R3）**：本项目测试环境未安装 Geyser/Floodgate，因此
  "监听器顺序最终生效结果"与"基岩皮肤是否保留"仍需在装有 Geyser + Floodgate 的服务器上实测。
