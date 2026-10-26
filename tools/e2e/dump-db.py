import sqlite3
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r'E:\MC\PluginTestServers\velocity\plugins\velocityauth\velocityauth.db'
db = sqlite3.connect(path)
c = db.cursor()
print('--- bindings ---')
for r in c.execute("select a.name, b.type, b.identifier from bindings b join accounts a on a.id=b.account_id order by b.id desc limit 5"):
    print(r)
print('--- audit ---')
for r in c.execute("select account_id, action, detail from audit_log order by id desc limit 5"):
    print(r)
print('--- accounts ---')
for r in c.execute("select id, name, last_server from accounts order by id desc limit 3"):
    print(r)
print('--- counts ---')
for t in ('accounts', 'identities', 'bindings', 'login_sessions', 'audit_log'):
    c.execute('select count(*) from ' + t)
    print(t, c.fetchone()[0])
