# =============================================================================
# 清理验证脚本留下的测试数据（只动测试账号与它们名下的资料，不碰真实数据）
#   1. 用测试账号登录，删掉自己名下的资料（走 API：同时清向量库与对象存储）；
#   2. 删除测试账号本身（SQL，数据库由调用方用 -SkipDb 控制跳过）。
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$AdminPassword = $env:ADMIN_PASSWORD,
  [string[]]$AdminOwnedTestKbIds = @(),
  # -File 传参时会把逗号分隔的值当成单个字符串，这里统一拆开
  [string]$TestUsers = '',
  [string]$Password = 'Isolate@12345',
  [switch]$SkipDb
)

$ErrorActionPreference = 'Continue'
$users = @($TestUsers -split '[,; ]' | Where-Object { $_ })

function Invoke-Api {
  param([string]$Method, [string]$Url, [hashtable]$Headers = @{}, $Body = $null)
  try {
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Depth 6 -Compress
      return Invoke-RestMethod -Uri $Url -Method $Method -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 90
    }
    return Invoke-RestMethod -Uri $Url -Method $Method -Headers $Headers -TimeoutSec 90
  }
  catch {
    $status = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { 'ERR' }
    return [pscustomobject]@{ code = "HTTP_$status"; message = $_.Exception.Message }
  }
}

if ($AdminPassword) {
  $admin = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = 'admin'; password = $AdminPassword }
  if ($admin.data.accessToken) {
    $ah = @{ Authorization = "Bearer $($admin.data.accessToken)" }
    foreach ($kbId in $AdminOwnedTestKbIds) {
      "admin 删除测试资料 id=$kbId -> $((Invoke-Api Delete "$Base/api/knowledgebase/$kbId" $ah).code)"
    }
  } else {
    "admin 登录失败，跳过管理员名下测试资料清理: $($admin.message)"
  }
}

foreach ($user in $users) {
  $login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $user; password = $Password }
  if (-not $login.data.accessToken) {
    "$user 登录失败（可能已删除）: $($login.message)"
    continue
  }
  $h = @{ Authorization = "Bearer $($login.data.accessToken)" }
  # 名下的试卷（走 API：顺带清切题记录、练习会话与对象存储里的文件）
  foreach ($paper in @((Invoke-Api Get "$Base/api/resumes" $h).data)) {
    "$user 删除试卷 id=$($paper.id) name=$($paper.filename) -> $((Invoke-Api Delete "$Base/api/resumes/$($paper.id)" $h).code)"
  }
  $kbs = @((Invoke-Api Get "$Base/api/knowledgebase/list" $h).data)
  foreach ($kb in $kbs) {
    # 只删自己名下的（共享资料属于管理员，不在这里删）
    if ($kb.visibility -eq 'PRIVATE') {
      "$user 删除资料 id=$($kb.id) name=$($kb.name) -> $((Invoke-Api Delete "$Base/api/knowledgebase/$($kb.id)" $h).code)"
    }
  }
}

if ($SkipDb) {
  '已跳过测试账号的数据库清理'
} else {
  $list = ($users | ForEach-Object { "'$_'" }) -join ','
  if ($list) {
    "请在数据库执行：DELETE FROM users WHERE username IN ($list);"
  }
}
