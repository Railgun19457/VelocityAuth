// VelocityAuth 实机端到端测试：注册 / 登录 / profile 重写 / 未认证隔离 / 记忆子服
//
// 前置：tools/setup-testenv.ps1 已执行，paper2612 + paper12111 + folia + velocity 均已启动。
// 用法：node auth-flow.mjs
//
// 说明：Velocity 默认 login-ratelimit=3000ms（按 IP），因此每次重连之间需要等待 >3s。
import fs from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25565)
// 1.20.4 < 1.20.5，用于验证 Transfer 不可用时的降级路径（断开 + 提示重连）
const VERSION = process.env.MC_VERSION || '1.20.4'
const PROXY_LOG = process.env.MC_PROXY_LOG || 'E:\\MC\\PluginTestServers\\velocity\\va-test-console.log'
const SETTLE = Number(process.env.MC_SETTLE_MS || 4200)
// 每次运行使用全新账号，保证测试可重复执行
const ACCOUNT = process.env.VA_ACCOUNT || 'va' + Math.random().toString(36).slice(2, 7)
const PASSWORD = 'Passw0rd'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function proxyLogLines() {
  try {
    return fs.readFileSync(PROXY_LOG, 'latin1').split(/\r?\n/)
  } catch {
    return []
  }
}

/** 从代理日志里取该玩家最后一次连接到的后端名。 */
function lastServerFor(name) {
  const pattern = new RegExp(`\\[server connection\\] ${name} -> (\\S+) has connected`)
  let found = null
  for (const line of proxyLogLines()) {
    const match = line.match(pattern)
    if (match) found = match[1]
  }
  return found
}

class Session {
  constructor(username, version = VERSION) {
    this.username = username
    this.messages = []
    this.spawned = false
    this.ended = null
    this.kicked = null
    this.bot = mineflayer.createBot({
      host: HOST,
      port: PORT,
      username,
      version,
      auth: 'offline',
      hideErrors: true,
    })
    this.bot.on('spawn', () => {
      this.spawned = true
    })
    this.bot.on('messagestr', (m) => this.messages.push(m))
    this.bot.on('kicked', (r) => {
      this.kicked = r
    })
    this.bot.on('end', (r) => {
      this.ended = r
    })
    this.bot.on('error', () => {})
  }

  get profileName() {
    return this.bot.username
  }

  get inPlay() {
    return this.bot.entity !== undefined && this.bot.entity !== null
  }

  async waitSpawn(timeout = 25000) {
    const start = Date.now()
    while (Date.now() - start < timeout) {
      if (this.spawned) return true
      if (this.ended !== null) return false
      await sleep(150)
    }
    return false
  }

  async waitEnd(timeout = 15000) {
    const start = Date.now()
    while (Date.now() - start < timeout) {
      if (this.ended !== null) return true
      await sleep(150)
    }
    return false
  }

  async waitMessage(pattern, timeout = 8000) {
    const start = Date.now()
    while (Date.now() - start < timeout) {
      const hit = this.messages.find((m) => pattern.test(m))
      if (hit) return hit
      await sleep(150)
    }
    return null
  }

  chat(text) {
    if (!this.inPlay) return false
    try {
      this.bot.chat(text)
      return true
    } catch {
      return false
    }
  }

  quit() {
    try {
      this.bot.quit()
    } catch {
      /* ignore */
    }
  }
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`)
}

/** 连接并在失败时给出诊断信息。 */
async function connectAndSpawn(username, label) {
  const session = new Session(username)
  const spawned = await session.waitSpawn()
  check(`${label}：成功进入服务器`, spawned,
    spawned ? '' : `end=${JSON.stringify(session.ended)} kicked=${JSON.stringify(session.kicked)}`)
  return session
}

async function phaseRegister() {
  console.log(`\n=== 阶段 1：注册与 profile 重写（账号 ${ACCOUNT}）===`)
  const s = await connectAndSpawn('Test2', '未认证玩家')
  if (!s.spawned) return
  check('未认证玩家被路由到登录服 paper2612', lastServerFor('Test2') === 'paper2612', `实际=${lastServerFor('Test2')}`)
  const prompt = await s.waitMessage(/请先登录/, 5000)
  check('收到未认证提示', !!prompt, prompt || '（无提示）')

  s.messages.length = 0
  s.chat('/spawn')
  const denied = await s.waitMessage(/请先登录/, 5000)
  check('未认证时非认证命令被拦截', !!denied, denied || '（未拦截）')

  s.messages.length = 0
  s.chat(`/login ${ACCOUNT} wrongpass`)
  const wrong = await s.waitMessage(/账号或密码错误/, 8000)
  check('未注册账号登录返回统一错误', !!wrong, wrong || '（无响应）')

  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} ${PASSWORD} ${PASSWORD}`)
  const success = await s.waitMessage(/注册成功/, 15000)
  check('注册成功提示', !!success, success || '（无响应）')
  const ended = await s.waitEnd(15000)
  check('注册后触发重连（降级为断开）', ended, `end=${s.ended}`)
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAndSpawn('Test2', '重连')
  check(`profile 已重写为账号名 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际 profileName=${s2.profileName}`)
  check('已认证玩家被路由到主服 paper12111', lastServerFor(ACCOUNT) === 'paper12111',
    `实际=${lastServerFor(ACCOUNT)}`)
  s2.quit()
  await sleep(SETTLE)
}

async function phaseLogin() {
  console.log('\n=== 阶段 2：用其它连接名登录同一账号 ===')
  const s = await connectAndSpawn('Test3', '连接名 Test3')
  if (!s.spawned) return
  s.messages.length = 0
  s.chat(`/login ${ACCOUNT} ${PASSWORD}`)
  const success = await s.waitMessage(/登录成功/, 15000)
  check('登录成功提示', !!success, success || '（无响应）')
  const ended = await s.waitEnd(15000)
  check('登录后触发重连', ended, `end=${s.ended}`)
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAndSpawn('Test3', '登录后重连')
  check(`连接名 Test3 登录后表现为 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际=${s2.profileName}`)
  s2.quit()
  await sleep(SETTLE)
}

async function phaseWrongPassword() {
  console.log('\n=== 阶段 3：错误密码 ===')
  const s = await connectAndSpawn('Test4', '连接名 Test4')
  if (!s.spawned) return
  s.messages.length = 0
  s.chat(`/login ${ACCOUNT} NotThePassword`)
  const wrong = await s.waitMessage(/账号或密码错误/, 15000)
  check('错误密码被拒绝', !!wrong, wrong || '（无响应）')
  check('错误密码不会重写 profile', s.profileName === 'Test4', `实际=${s.profileName}`)
  check('错误密码后仍停留在登录服', lastServerFor('Test4') === 'paper2612', `实际=${lastServerFor('Test4')}`)
  s.quit()
  await sleep(SETTLE)
}

async function phaseDuplicateRegister() {
  console.log('\n=== 阶段 4：重复注册 / 非法账号名 ===')
  const s = await connectAndSpawn('Test5', '连接名 Test5')
  if (!s.spawned) return
  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} Another1 Another1`)
  const taken = await s.waitMessage(/已被占用/, 15000)
  check('重复账号名被拒绝', !!taken, taken || '（无响应）')

  s.messages.length = 0
  s.chat('/register ab x')
  const invalid = await s.waitMessage(/账号名不合法/, 8000)
  check('过短账号名被拒绝', !!invalid, invalid || '（无响应）')

  s.messages.length = 0
  s.chat('/register Test6 abcdef NotSame')
  const mismatch = await s.waitMessage(/不一致/, 8000)
  check('两次密码不一致被拒绝', !!mismatch, mismatch || '（无响应）')
  s.quit()
  await sleep(SETTLE)
}

async function phaseRememberLastServer() {
  console.log('\n=== 阶段 5：记忆上次所在子服 ===')
  const s = await connectAndSpawn('Test7', '连接名 Test7')
  if (!s.spawned) return
  s.messages.length = 0
  s.chat(`/login ${ACCOUNT} ${PASSWORD}`)
  await s.waitMessage(/登录成功/, 15000)
  await s.waitEnd(15000)
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAndSpawn('Test7', '登录后重连')
  check('先进入默认主服 paper12111', lastServerFor(ACCOUNT) === 'paper12111', `实际=${lastServerFor(ACCOUNT)}`)

  // 切到 folia，断开后再次登录应回到 folia
  s2.messages.length = 0
  s2.chat('/server folia')
  await sleep(4000)
  const onFolia = lastServerFor(ACCOUNT) === 'folia'
  check('可切换到白名单子服 folia', onFolia, `实际=${lastServerFor(ACCOUNT)}`)
  s2.quit()
  await sleep(SETTLE)

  const s3 = await connectAndSpawn('Test8', '连接名 Test8')
  if (!s3.spawned) return
  s3.messages.length = 0
  s3.chat(`/login ${ACCOUNT} ${PASSWORD}`)
  await s3.waitMessage(/登录成功/, 15000)
  await s3.waitEnd(15000)
  s3.quit()
  await sleep(SETTLE)

  const s4 = await connectAndSpawn('Test8', '再次登录重连')
  check('自动回到上次所在的 folia', lastServerFor(ACCOUNT) === 'folia', `实际=${lastServerFor(ACCOUNT)}`)
  s4.quit()
  await sleep(SETTLE)
}

async function main() {
  try {
    await phaseRegister()
    await phaseLogin()
    await phaseWrongPassword()
    await phaseDuplicateRegister()
    await phaseRememberLastServer()
  } catch (e) {
    console.error('测试异常：', e)
    results.push({ name: 'unexpected-error', ok: false, detail: String(e) })
  }
  const failed = results.filter((r) => !r.ok)
  console.log(`\n===== 结果：${results.length - failed.length}/${results.length} 通过 =====`)
  if (failed.length) {
    console.log('失败项：')
    failed.forEach((f) => console.log(`  - ${f.name} ${f.detail}`))
  }
  process.exit(failed.length ? 1 : 0)
}

main()
