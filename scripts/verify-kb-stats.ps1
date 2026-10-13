# =============================================================================
# 资料库统计 / 共享标识 验证
#   1. 统计接口按归属过滤：管理员默认只看自己的，?scope=all 才看全部；
#   2. 管理员上传 SHARED 资料后，列表 DTO 带出 visibility=SHARED（前端徽标依赖它）；
#   3. SHARED 资料对普通用户可见（可读不可改）。
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$AdminUser = 'admin',
  [string]$AdminPassword = $env:ADMIN_PASSWORD,
  [string]$SharedFile = 'docs/test-papers/英语-测试卷.txt'
)

if (-not $AdminPassword) {
  Write-Error '缺少 admin 密码：请通过 $env:ADMIN_PASSWORD 传入'
  exit 1
}

$ErrorActionPreference = 'Continue'

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

$admin = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $AdminUser; password = $AdminPassword }
if (-not $admin.data.accessToken) { Write-Error "admin 登录失败: $($admin.message)"; exit 1 }
$ah = @{ Authorization = "Bearer $($admin.data.accessToken)" }

$mine = (Invoke-Api Get "$Base/api/knowledgebase/stats" $ah).data
$all = (Invoke-Api Get "$Base/api/knowledgebase/stats?scope=all" $ah).data
"admin 默认统计 : 资料总数=$($mine.totalCount) 提问=$($mine.totalQuestionCount) 访问=$($mine.totalAccessCount) 已完成=$($mine.completedCount)"
"admin scope=all: 资料总数=$($all.totalCount) 提问=$($all.totalQuestionCount) 访问=$($all.totalAccessCount) 已完成=$($all.completedCount)"
"期望：默认统计 <= scope=all 统计（默认只看自己的）"
''

# 建一个临时普通用户（走管理员接口，避免 /api/auth/register 的限流），验证 SHARED 资料对他可见
$name = 'isosh' + (Get-Random -Maximum 99999)
$created = Invoke-Api Post "$Base/api/admin/users" $ah @{ username = $name; password = 'Isolate@12345' }
$login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $name; password = 'Isolate@12345' }
if (-not $login.data.accessToken) { Write-Error "普通用户登录失败: $($login.message)"; exit 1 }
$bh = @{ Authorization = "Bearer $($login.data.accessToken)" }

$uploadJson = & curl.exe -s -X POST "$Base/api/knowledgebase/upload?visibility=SHARED" `
  -H "Authorization: Bearer $($admin.data.accessToken)" `
  -F "file=@$SharedFile" -F 'name=共享验证卷'
$up = $uploadJson | ConvertFrom-Json
"管理员上传共享资料: id=$($up.data.knowledgeBase.id) duplicate=$($up.data.duplicate)"

# 普通用户尝试用 visibility=SHARED 上传（应被降级为 PRIVATE）
$privateJson = & curl.exe -s -X POST "$Base/api/knowledgebase/upload?visibility=SHARED" `
  -H "Authorization: Bearer $($login.data.accessToken)" `
  -F "file=@$SharedFile" -F 'name=普通用户伪装共享'
$pv = $privateJson | ConvertFrom-Json
"普通用户伪共享上传: id=$($pv.data.knowledgeBase.id) duplicate=$($pv.data.duplicate)（期望 duplicate=false：即便哈希与上面相同也各存一份）"
''

$bList = @((Invoke-Api Get "$Base/api/knowledgebase/list" $bh).data)
"普通用户 B 可见资料数=$($bList.Count)（应为 2：自己那份 PRIVATE + 管理员那份 SHARED）"
$bList | ForEach-Object { "  - id=$($_.id) name=$($_.name) visibility=$($_.visibility)" }
''

$aList = @((Invoke-Api Get "$Base/api/knowledgebase/list" $ah).data)
"admin 列表（默认只看自己的）: $($aList.Count) 条"
$aList | ForEach-Object { "  - id=$($_.id) name=$($_.name) visibility=$($_.visibility)" }
''

$mine2 = (Invoke-Api Get "$Base/api/knowledgebase/stats" $ah).data
$all2 = (Invoke-Api Get "$Base/api/knowledgebase/stats?scope=all" $ah).data
"上传后再比一次统计：默认 totalCount=$($mine2.totalCount) / scope=all totalCount=$($all2.totalCount)"
"期望：默认只统计「自己的 + 共享」（1,2,4 + 15 = 4），scope=all 还要算上测试用户的（+16 = 5）"
if ($all2.totalCount -le $mine2.totalCount) { $script:statsOk = $false } else { $script:statsOk = $true }
if (-not $statsOk) { '❌ 统计口径没有按归属收窄' } else { '✅ 统计口径按归属收窄' }
''

"【清理】"
foreach ($kb in @((Invoke-Api Get "$Base/api/knowledgebase/list" $bh).data)) {
  if ($kb.visibility -eq 'PRIVATE') {
    "删除测试用户资料 id=$($kb.id) -> $((Invoke-Api Delete "$Base/api/knowledgebase/$($kb.id)" $bh).code)"
  }
}
if ($up.data.knowledgeBase.id) {
  "删除管理员测试资料 id=$($up.data.knowledgeBase.id) -> $((Invoke-Api Delete "$Base/api/knowledgebase/$($up.data.knowledgeBase.id)" $ah).code)"
}
if ($created.data.id) {
  "删除测试账号 $name -> $((Invoke-Api Delete "$Base/api/admin/users/$($created.data.id)" $ah).code)"
}
