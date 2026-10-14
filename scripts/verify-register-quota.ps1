# =============================================================================
# 自助注册配额验证（本地验证实例 8081）
#
# 覆盖两条上限：
#   1) 同一来源 IP 24 小时内最多成功注册 N 个（默认 3）
#   2) 全站每日最多成功自助注册 M 个（默认 5），管理员建号不计数
# 用 X-Forwarded-For 模拟不同来源网络（ClientIpResolver 取第一段）。
#
# 用法：pwsh -File scripts/verify-register-quota.ps1   （需先起 scripts/run-verify-app.ps1）
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [int]$PerIpLimit = 3,
  [int]$GlobalLimit = 5,
  [string]$PgContainer = 'my_pgvector',
  [string]$PgDatabase = 'smart-quiz',
  [string]$PgUser = 'postgres'
)

$ErrorActionPreference = 'Continue'
$password = 'Quota@12345678'
$stamp = Get-Random -Maximum 999999
$ipA = "10.9.9.$((Get-Random -Maximum 200) + 1)"
$ipB = "10.9.9.$((Get-Random -Maximum 200) + 1)"
$failures = @()

function PgExec {
  param([string]$Sql)
  return ((& docker exec $PgContainer psql -U $PgUser -d $PgDatabase -t -A -c $Sql 2>&1 | Out-String)).Trim()
}

function Register-One {
  param([string]$Username, [string]$ClientIp)
  try {
    $body = @{ username = $Username; password = $password } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Uri "$Base/api/auth/register" -Method Post `
      -ContentType 'application/json; charset=utf-8' `
      -Headers @{ 'X-Forwarded-For' = $ClientIp } `
      -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
    return @{ code = $resp.code; message = $resp.message }
  } catch {
    $text = ''
    try { $text = (New-Object System.IO.StreamReader($_.Exception.Response.Content.ReadAsStream())).ReadToEnd() } catch {}
    if ($text) { try { $r = $text | ConvertFrom-Json; return @{ code = $r.code; message = $r.message } } catch {} }
    return @{ code = 'HTTP_ERR'; message = $_.Exception.Message }
  }
}

function Check {
  param([string]$Label, $Result, [string]$ExpectCode, [string]$ExpectInMessage = '')
  $ok = ("$($Result.code)" -eq $ExpectCode)
  if ($ok -and $ExpectInMessage) { $ok = ("$($Result.message)" -like "*$ExpectInMessage*") }
  $msg = ($Result.message -replace '\s+', ' ')
  if (-not $ok) { $script:failures += "$Label -> code=$($Result.code) $msg（期望 $ExpectCode $ExpectInMessage）" }
  "{0,-34} code={1,-6} {2}" -f $Label, $Result.code, $msg
}

"来源网络 A=$ipA  B=$ipB  期望：A 最多 $PerIpLimit 个、全站最多 $GlobalLimit 个"
''

"【同一 IP 的上限】"
for ($i = 1; $i -le $PerIpLimit; $i++) {
  Check "A 第 $i 个注册" (Register-One -Username "quota${stamp}a$i" -ClientIp $ipA) '200'
}
Check "A 第 $($PerIpLimit + 1) 个注册（应被拒）" (Register-One -Username "quota${stamp}a9" -ClientIp $ipA) '12014' '同一网络'
''

"【全站每日上限（换一个网络继续注册）】"
$remaining = $GlobalLimit - $PerIpLimit
for ($i = 1; $i -le $remaining; $i++) {
  Check "B 第 $i 个注册（凑满全站 $GlobalLimit 个）" (Register-One -Username "quota${stamp}b$i" -ClientIp $ipB) '200'
}
Check "B 第 $($remaining + 1) 个注册（应被拒）" (Register-One -Username "quota${stamp}b9" -ClientIp $ipB) '12014' '今日注册名额已用完'
''

"【数据库核对】"
$selfReg = PgExec "SELECT count(*) FROM users WHERE username LIKE 'quota${stamp}%' AND registered_ip IS NOT NULL;"
"带 registered_ip 的新账号 = $selfReg（期望 $GlobalLimit）"
if ($selfReg -ne "$GlobalLimit") { $failures += "实际入库的自助注册账号数不对：$selfReg" }
$ipCountA = PgExec "SELECT count(*) FROM users WHERE registered_ip = '$ipA';"
"来源 A 的账号 = $ipCountA（期望 $PerIpLimit）"
if ($ipCountA -ne "$PerIpLimit") { $failures += "A 网络入库数不对：$ipCountA" }
''

"【清理】"
PgExec "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'quota${stamp}%');" | Out-Null
PgExec "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'quota${stamp}%');" | Out-Null
PgExec "DELETE FROM users WHERE username LIKE 'quota${stamp}%';" | Out-Null
"已删除测试账号（quota${stamp}*）"
''

if ($failures.Count -eq 0) { '✅ 注册配额全部符合预期' } else {
  "❌ 发现 $($failures.Count) 处问题："
  $failures | ForEach-Object { "  - $_" }
}
