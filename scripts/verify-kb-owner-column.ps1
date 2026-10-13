# =============================================================================
# 「查看全部」列表带上传人验证
#
# 造两个测试用户各上传**同一份文件**，再临时把其中一个提成管理员（用完撤销），
# 让他用 ?scope=all 拉列表，确认每行都能看出是谁上传的。
#
# 用法：pwsh -File scripts/verify-kb-owner-column.ps1
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$KbFile = 'docs/test-papers/小学数学知识点汇总（1）.txt',
  [string]$PgContainer = 'my_pgvector',
  [string]$PgDatabase = 'smart-quiz',
  [string]$PgUser = 'postgres'
)

$ErrorActionPreference = 'Continue'

function Invoke-Api {
  param([string]$Method, [string]$Url, [hashtable]$Headers = @{}, $Body = $null)
  try {
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Depth 6 -Compress
      return Invoke-RestMethod -Uri $Url -Method $Method -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 120
    }
    return Invoke-RestMethod -Uri $Url -Method $Method -Headers $Headers -TimeoutSec 120
  }
  catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $text = ''
    try { $text = (New-Object System.IO.StreamReader($_.Exception.Response.Content.ReadAsStream())).ReadToEnd() } catch {}
    if ($text) { try { return ($text | ConvertFrom-Json) } catch {} }
    return [pscustomobject]@{ code = "HTTP_$status"; message = $_.Exception.Message }
  }
}

function PgExec {
  param([string]$Sql)
  return ((& docker exec $PgContainer psql -U $PgUser -d $PgDatabase -t -A -c $Sql 2>&1 | Out-String)).Trim()
}

$password = 'Owner@12345678'
$created = @()
foreach ($prefix in @('ownA', 'ownB')) {
  $name = $prefix + (Get-Random -Maximum 9999)
  $reg = Invoke-Api Post "$Base/api/auth/register" @{} @{ username = $name; password = $password }
  if ($reg.code -and $reg.code -ne 200) { Write-Error "注册 $name 失败: $($reg.message)"; exit 1 }
  $login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $name; password = $password }
  if (-not $login.data.accessToken) { Write-Error "$name 登录失败"; exit 1 }
  $up = (& curl.exe -s -X POST "$Base/api/knowledgebase/upload" `
    -H "Authorization: Bearer $($login.data.accessToken)" `
    -F "file=@$KbFile" -F "name=$prefix-同名资料") | ConvertFrom-Json
  $created += [pscustomobject]@{
    user = $name; token = $login.data.accessToken
    kbId = $up.data.knowledgeBase.id
  }
  "$name 上传同一份文件 -> kbId=$($up.data.knowledgeBase.id)"
}
''

# 临时把 A 提成管理员，才能走 ?scope=all
$userA = $created[0]
PgExec "INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r WHERE u.username='$($userA.user)' AND r.code='ROLE_ADMIN' ON CONFLICT DO NOTHING;" | Out-Null
$adminLogin = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $userA.user; password = $password }
if (-not $adminLogin.data.accessToken) { Write-Error "提权后登录失败"; exit 1 }
$ah = @{ Authorization = "Bearer $($adminLogin.data.accessToken)" }
$roles = ($adminLogin.data.user.roles -join ',')
"$($userA.user) 的角色 = $roles（期望含 ROLE_ADMIN）"
''

$rows = @((Invoke-Api Get "$Base/api/knowledgebase/list?scope=all" $ah).data)
"scope=all 看到 $($rows.Count) 条资料："
$rows | ForEach-Object { "  id=$($_.id) 名称=$($_.name) 上传人=$($_.ownerUsername)（ownerId=$($_.ownerId)）visibility=$($_.visibility)" }
''

$owners = $rows | ForEach-Object { $_.ownerUsername } | Sort-Object -Unique
"不同上传人 = $($owners -join '、')"
if ($owners.Count -ge 3) { "✅ 能区分上传人（含 admin、$($created[0].user)、$($created[1].user)）" }
else { "❌ 上传人区分不对，只有：$($owners -join '、')" }
$matched = $rows | Where-Object { $_.ownerUsername -eq $userA.user }
if ($matched) { "✅ 该用户的资料行上传人字段正确（$($matched.Count) 条）" } else { "❌ 没找到带上传人=$($userA.user) 的行" }
''

# ---------- 清理：撤销临时管理员权限、删资料与测试账号 ----------
"【清理】"
PgExec "DELETE FROM user_roles WHERE user_id=(SELECT id FROM users WHERE username='$($userA.user)') AND role_id=(SELECT id FROM roles WHERE code='ROLE_ADMIN');" | Out-Null
"已撤销 $($userA.user) 的临时管理员权限"
foreach ($c in $created) {
  $h = @{ Authorization = "Bearer $($c.token)" }
  "删除资料 id=$($c.kbId) -> $((Invoke-Api Delete "$Base/api/knowledgebase/$($c.kbId)" $h).code)"
}
$names = ($created | ForEach-Object { "'$($_.user)'" }) -join ','
PgExec "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE username IN ($names));" | Out-Null
PgExec "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ($names));" | Out-Null
PgExec "DELETE FROM users WHERE username IN ($names);" | Out-Null
"已删除测试账号：$names"
