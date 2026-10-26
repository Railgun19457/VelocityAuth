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
- 代码尚未开始，按 `DESIGN.md` 第 15 节的 M1–M9 里程碑推进

## 关键约定速查

| 项 | 值 |
|---|---|
| 目录 / 包 / 插件 id | `VelocityAuth` / `io.github.railgun19457.velocityauth` / `velocityauth` |
| JDK / 代理 API | Java 21 / velocity-api 3.5.1 |
| 配置 / 消息 | YAML / 中英双语 i18n |
| 数据库 | SQLite（默认）/ MySQL |
| bot 协议 | WebSocket，插件作服务端；Ed25519 身份 + X25519/AES-GCM 会话加密 |
| 协议前缀 | `VELOCITYAUTH-*` |
