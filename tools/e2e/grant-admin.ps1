# 给测试环境里的玩家授予 velocityauth.admin（直接写 LuckPerms 的 H2 库）。
# 仅在 LuckPerms 使用 h2 存储且服务器已停止时使用。
# 用法：pwsh -File tools/e2e/grant-admin.ps1 -Player <玩家名>
param([Parameter(Mandatory = $true)][string]$Player)
$ErrorActionPreference = 'Stop'

$lp = 'E:\MC\PluginTestServers\velocity\plugins\luckperms'
$h2 = Join-Path $lp 'libs\h2-driver-2.1.214.jar'

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*velocity.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

$url = 'jdbc:h2:' + (Join-Path $lp 'luckperms-h2-v2') + ';AUTO_SERVER=FALSE'
$tmp = Join-Path $env:TEMP 'lp-grant'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$runner = Join-Path $tmp 'Grant.java'

@"
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class Grant {
    /** Velocity 的离线 UUID 算法：UUID.nameUUIDFromBytes("OfflinePlayer:" + name)。 */
    static UUID offlineUuid(String name) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5")
                    .digest(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x30);   // version 3
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);   // IETF variant
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            return new UUID(msb, lsb);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        String player = args[1];
        Set<UUID> uuids = new LinkedHashSet<>();
        uuids.add(offlineUuid(player));
        uuids.add(offlineUuid(player.toLowerCase()));
        String[] perms = {"velocityauth.admin", "velocityauth.admin.forcelogin"};

        try (Connection c = DriverManager.getConnection(args[0], "", "")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM luckperms_group_permissions WHERE permission LIKE 'velocityauth%'");
            }
            for (UUID uuid : uuids) {
                System.out.println("grant to " + player + " uuid=" + uuid);
                // 确保存在玩家记录（LuckPerms 通常首次登录时创建）
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO luckperms_players (uuid, username, primary_group) KEY(uuid) VALUES (?, ?, 'default')")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, player);
                    ps.executeUpdate();
                }
                for (String perm : perms) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO luckperms_user_permissions (id, uuid, permission, \"VALUE\", server, world, expiry, contexts)"
                                    + " SELECT (SELECT COALESCE(MAX(id),0)+1 FROM luckperms_user_permissions), ?, ?, TRUE, 'global', 'global', 0, '{}'"
                                    + " WHERE NOT EXISTS (SELECT 1 FROM luckperms_user_permissions x WHERE x.uuid = ? AND x.permission = ?)")) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, perm);
                        ps.setString(3, uuid.toString());
                        ps.setString(4, perm);
                        ps.executeUpdate();
                    }
                }
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM luckperms_user_permissions WHERE permission LIKE 'velocityauth%'")) {
                rs.next();
                System.out.println("user-admin-perms = " + rs.getInt(1));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM luckperms_group_permissions WHERE permission LIKE 'velocityauth%'")) {
                rs.next();
                System.out.println("group-admin-perms = " + rs.getInt(1));
            }
        }
    }
}
"@ | Set-Content $runner -Encoding UTF8

& 'D:\Java\25\bin\java.exe' -cp $h2 $runner $url $Player
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
