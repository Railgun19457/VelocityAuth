# 生成一个「LibreLogin 旧库」用于迁移实机测试（表结构与 kyngs/LibreLogin 一致）。
# 输出 JSON：{ "path": ..., "bcryptAccount": ..., "sha256Account": ..., "password": ... }
param([string]$RunId = (Get-Random -Minimum 100000 -Maximum 999999))
$ErrorActionPreference = 'Stop'

$dir = 'E:\MC\PluginTestServers\velocity\plugins\librelogin'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$db = Join-Path $dir 'librepremium_data.db'
Remove-Item $db -Force -ErrorAction SilentlyContinue

# "LegacyPass1" 的 bcrypt(cost=10) 哈希（由 at.favre.lib:bcrypt 生成）
$bcrypt = '$2a$10$X.ddt4R266Fs37TVetxnTOVYf5zVOrPkYfuM5KxdeX0YzrJw.T5s2'
$bcryptAccount = "Legacy${RunId}A"
$sha256Account = "Legacy${RunId}B"
$emailAccount = "Legacy${RunId}C"

$py = @'
import hashlib, json, sqlite3, sys, uuid

db, bcrypt, bcrypt_account, sha256_account, email_account = sys.argv[1:6]

def offline(name):
    digest = hashlib.md5(('OfflinePlayer:' + name).encode('utf-8')).digest()[:16]
    return str(uuid.UUID(bytes=digest, version=3))

conn = sqlite3.connect(db)
c = conn.cursor()
c.execute("""CREATE TABLE librepremium_data(
    uuid VARCHAR(255) NOT NULL PRIMARY KEY,
    premium_uuid VARCHAR(255) UNIQUE,
    hashed_password VARCHAR(255),
    salt VARCHAR(255),
    algo VARCHAR(255),
    last_nickname VARCHAR(255) NOT NULL UNIQUE,
    joined TIMESTAMP NULL DEFAULT NULL,
    last_seen TIMESTAMP NULL DEFAULT NULL,
    last_server VARCHAR(255),
    secret VARCHAR(255),
    ip VARCHAR(255),
    last_authentication TIMESTAMP NULL DEFAULT NULL,
    email VARCHAR(255))""")

# 1) bcrypt：应可直接复用
c.execute("INSERT INTO librepremium_data(uuid, hashed_password, algo, last_nickname, last_seen, last_server, email)"
          " VALUES (?,?,?,?,?,?,?)",
          (offline(bcrypt_account), bcrypt, 'BCRYPT', bcrypt_account, '2024-05-06 07:08:09', 'survival',
           'legacy' + bcrypt_account.lower() + '@qq.com'))
# 2) SHA256：无法转换，应标记为需重置
c.execute("INSERT INTO librepremium_data(uuid, hashed_password, salt, algo, last_nickname)"
          " VALUES (?,?,?,?,?)",
          (offline(sha256_account),
           hashlib.sha256(b'LegacyPass1').hexdigest(), 'somesalt', 'SHA256', sha256_account))
# 3) 只有邮箱、无 last_server
c.execute("INSERT INTO librepremium_data(uuid, hashed_password, algo, last_nickname, email)"
          " VALUES (?,?,?,?,?)",
          (offline(email_account), bcrypt, 'BCRYPT', email_account,
           'legacy' + email_account.lower() + '@qq.com'))
conn.commit()
conn.close()
print(json.dumps({"path": db, "bcryptAccount": bcrypt_account, "sha256Account": sha256_account,
                  "emailAccount": email_account}))
'@
$pyFile = Join-Path $env:TEMP 'make-legacy-db.py'
$py | Set-Content $pyFile -Encoding UTF8
python $pyFile $db $bcrypt $bcryptAccount $sha256Account $emailAccount
Remove-Item $pyFile -Force -ErrorAction SilentlyContinue
