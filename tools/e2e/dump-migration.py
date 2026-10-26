import sqlite3

db = sqlite3.connect(r'E:\MC\PluginTestServers\velocity\plugins\velocityauth\velocityauth.db')
c = db.cursor()

print('--- 最近迁移的账号 ---')
for row in c.execute("select id, name, name_lower, substr(password_hash,1,20), last_server, last_login_at"
                     " from accounts where name_lower like 'legacy%' order by id desc limit 4"):
    print(' ', row)

print('--- 迁移出的邮箱绑定 ---')
for row in c.execute("select a.name, b.type, b.identifier from bindings b"
                     " join accounts a on a.id = b.account_id"
                     " where a.name_lower like 'legacy%'"):
    print(' ', row)

print('--- 身份映射 ---')
for row in c.execute("select kind, identifier from identities where identifier like 'legacy%'"):
    print(' ', row)

print('--- 迁移审计 ---')
for row in c.execute("select action, detail from audit_log where action = 'migrate' order by id desc limit 2"):
    print(' ', row)
