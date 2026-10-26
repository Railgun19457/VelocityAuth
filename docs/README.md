# VelocityAuth 文档

Velocity 代理端认证插件 —— 账号体系 + 多平台共用 + 可选验证门槛 + 邮箱/QQ 找回。

## 文档索引

| 文档 | 读者 | 内容 |
|---|---|---|
| [`DESIGN.md`](DESIGN.md) | 全体 | **设计文档**：目标、架构、核心机制、验证会话、数据模型、配置、命令、安全、风险、里程碑 |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | 插件开发者 | **开发须知**：技术栈、工程结构、依赖、Velocity API 参考、实现坑、测试策略、决策记录（ADR） |
| [`PROTOCOL.md`](PROTOCOL.md) | bot 开发者 | **bot 协议契约**：帧格式、签名、配对、会话认证、密钥派生、密封帧、业务消息、安全要求 |
| [`protocol/vectors/`](protocol/vectors) | bot + 插件 | **测试向量**：确定性、可复现，双方都必须通过 |
| [`protocol/tools/gen_vectors.py`](protocol/tools/gen_vectors.py) | — | 向量生成脚本 |

## 快速导航

- 想了解**为什么这么设计** → `DESIGN.md` 第 4 节 + `IMPLEMENTATION.md` 第 8 节（ADR）
- 想**开始写插件** → `IMPLEMENTATION.md` 第 1–5 节
- 想**写 bot 侧** → `PROTOCOL.md` 全文 + `protocol/vectors/`
- 想知道**还剩什么没定** → `DESIGN.md` 附录「待评审确认项」

## 当前状态

- 设计已定稿，唯一待定项：服务器键名（`velocity.toml` 的 `[servers]` 段）
- **代码已实现 M1–M9**（见 `DESIGN.md` 第 15 节），每阶段均有本地提交与实机验证
- 尚未在真实 Geyser + Floodgate 环境实测（风险 R2/R3），详见 `IMPLEMENTATION.md` 的 ADR-15

## 里程碑完成情况

| 里程碑 | 状态 | 实机验证 |
|---|---|---|
| M1 骨架 | ✅ | 插件加载、配置释放、i18n |
| M2 数据层 | ✅ | SQLite 建库（schema v1） |
| M3 认证核心 | ✅ | 30/30（注册/重连改写 profile/换名登录/未认证隔离/记忆子服） |
| M4 验证会话 | ✅ | email 13/13、QQ 12/12（含"游戏内不能提交 QQ 码"防绕过） |
| M5 绑定与找回 | ✅ | 33/33（绑定/解绑/找回/重置/防枚举） |
| M6 bot 协议 | ✅ | 测试向量逐字节一致 + 完整 pair/auth/verify 10/10 |
| M7 多平台 | ✅ | 回归 30/30 + 33/33；Floodgate 未安装时降级 |
| M8 运维 | ✅ | 37/37（状态/账号/会话/强制登录/权限/审计） |
| M9 迁移 | ✅ | 15/15（LibreLogin 旧库 → 账号/哈希/邮箱/身份） |

## 测试环境

- 实机环境：`E:\MC\PluginTestServers`（Velocity 3.5.1 + 2×Paper + Folia）
- 一键准备：`pwsh -File tools/setup-testenv.ps1`（转离线模式 + 生成配置 + 部署）
- 端到端用例：`tools/e2e/`（mineflayer 玩家端 + Node bot 参考实现 + 管理命令用例）

## 关键约定速查

| 项 | 值 |
|---|---|
| 目录 / 包 / 插件 id | `VelocityAuth` / `io.github.railgun19457.velocityauth` / `velocityauth` |
| JDK / 代理 API | Java 21 / velocity-api 3.5.1 |
| 配置 / 消息 | YAML / 中英双语 i18n |
| 数据库 | SQLite（默认）/ MySQL |
| bot 协议 | WebSocket，插件作服务端；Ed25519 身份 + X25519/AES-GCM 会话加密 |
| 协议前缀 | `VELOCITYAUTH-*` |
