# =============================================================================
# 用户数据隔离 · 越权验证脚本
#
# 用法（需先启动后端）：
#   $env:ADMIN_PASSWORD='<admin 密码>'; pwsh -File scripts/verify-isolation.ps1
#
# 做两件事：
#   1. 注册一个全新普通用户 B，验证 B 看不到、也动不了 admin 的试卷/资料/会话/轨迹；
#   2. 验证 B 上传同一份文件两次会被判重（按"本人"去重，不会串到别人的记录）。
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$AdminUser = 'admin',
  [string]$AdminPassword = $env:ADMIN_PASSWORD,
  [string]$TestFile = 'docs/test-papers/数学-测试卷.txt'
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
      $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
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

function Show([string]$Label, $Result, [string]$Expect) {
  $code = if ($null -ne $Result.code) { $Result.code } else { '200' }
  $msg = if ($Result.message) { ($Result.message -replace '\s+', ' ') } else { '' }
  $plain = "$Label -> code=$code $msg".Trim()
  "{0,-52} 期望: {1}" -f $plain, $Expect
}

$admin = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $AdminUser; password = $AdminPassword }
if (-not $admin.data.accessToken) { Write-Error "admin 登录失败: $($admin.message)"; exit 1 }
$ah = @{ Authorization = "Bearer $($admin.data.accessToken)" }

$papers = (Invoke-Api Get "$Base/api/resumes" $ah).data
$kbs = (Invoke-Api Get "$Base/api/knowledgebase/list" $ah).data
$paperId = @($papers)[0].id
$kbId = @($kbs)[0].id
"admin 可见: 试卷=$(@($papers).Count) 资料=$(@($kbs).Count)  (paperId=$paperId kbId=$kbId)"
''

# ---------- 用管理员接口建号：/api/auth/register 限流 5 次/小时/IP，反复跑会撞限流 ----------
$name = 'iso' + (Get-Random -Maximum 99999)
$created = Invoke-Api Post "$Base/api/admin/users" $ah @{ username = $name; password = 'Isolate@12345' }
$login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $name; password = 'Isolate@12345' }
if (-not $login.data.accessToken) { Write-Error "普通用户 B 登录失败: $($login.message)"; exit 1 }
$bh = @{ Authorization = "Bearer $($login.data.accessToken)" }
"普通用户 B = $name"
''

"【B 自己的列表】"
Show 'B 试卷列表条数' @{ code = (Invoke-Api Get "$Base/api/resumes" $bh).data.Count } '0'
Show 'B 测验记录条数' @{ code = (Invoke-Api Get "$Base/api/quiz/history" $bh).data.Count } '0'
Show 'B 资料列表条数' @{ code = (Invoke-Api Get "$Base/api/knowledgebase/list" $bh).data.Count } '0'
Show 'B 轨迹统计 total' @{ code = (Invoke-Api Get "$Base/api/rag-chat/traces/summary" $bh).data.total } '0'
''

"【越权访问 admin 的资源：期望全部 404】"
if ($paperId) {
  Show 'B 取 admin 试卷详情' (Invoke-Api Get "$Base/api/resumes/$paperId/detail" $bh) '404'
  Show 'B 重切 admin 试卷' (Invoke-Api Post "$Base/api/resumes/$paperId/reanalyze" $bh) '404'
  Show 'B 删 admin 试卷' (Invoke-Api Delete "$Base/api/resumes/$paperId" $bh) '404'
}
if ($kbId) {
  Show 'B 取 admin 资料' (Invoke-Api Get "$Base/api/knowledgebase/$kbId" $bh) '404'
  Show 'B 改 admin 资料分类' (Invoke-Api Put "$Base/api/knowledgebase/$kbId/category" $bh '{"categoryCode":""}') '404'
  Show 'B 列 admin 资料题库' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions" $bh) '404'
  Show 'B 用 admin 资料开考' (Invoke-Api Post "$Base/api/knowledgebase-interviews/sessions" $bh @{ knowledgeBaseId = $kbId; difficulty = 'mid'; mainQuestionCount = 3; followUpCount = 0 }) '404'
  Show 'B 在问答范围塞 admin 资料' (Invoke-Api Post "$Base/api/rag-chat/sessions" $bh @{ scope = 'SELECTED'; knowledgeBaseIds = @($kbId) }) '404'
}
''

"【管理员查看全部（?scope=all）】"
$allPapers = Invoke-Api Get "$Base/api/resumes?scope=all" $ah
Show 'admin scope=all 试卷条数' @{ code = $allPapers.data.Count } '≥ 自己的条数'
''

"【同一文件两人上传：各自存一份】"
$before = @((Invoke-Api Get "$Base/api/knowledgebase/list" $bh).data).Count
$f1 = & curl.exe -s -X POST "$Base/api/knowledgebase/upload" -H "Authorization: Bearer $($login.data.accessToken)" -F "file=@$TestFile" -F 'name=隔离验证卷'
$f2 = & curl.exe -s -X POST "$Base/api/knowledgebase/upload" -H "Authorization: Bearer $($login.data.accessToken)" -F "file=@$TestFile" -F 'name=隔离验证卷'
$r1 = $f1 | ConvertFrom-Json
$r2 = $f2 | ConvertFrom-Json
"第一次: id=$($r1.data.knowledgeBase.id) duplicate=$($r1.data.duplicate)"
"第二次: id=$($r2.data.knowledgeBase.id) duplicate=$($r2.data.duplicate)  （期望 duplicate=true 且 id 与第一次相同）"
$after = @((Invoke-Api Get "$Base/api/knowledgebase/list" $bh).data).Count
"B 上传后自己可见资料=$after（上传前 $before，期望 +1）"
$adminAfter = @((Invoke-Api Get "$Base/api/knowledgebase/list" $ah).data).Count
"admin 资料数=$adminAfter（期望不变 = $(@($kbs).Count)）"
''

"【清理】"
# 删掉 B 名下的资料（走 API：同时清向量库与对象存储），再删测试账号
foreach ($kb in @((Invoke-Api Get "$Base/api/knowledgebase/list" $bh).data)) {
  if ($kb.visibility -eq 'PRIVATE') {
    "删除 B 的资料 id=$($kb.id) -> $((Invoke-Api Delete "$Base/api/knowledgebase/$($kb.id)" $bh).code)"
  }
}
if ($created.data.id) {
  "删除测试账号 $name -> $((Invoke-Api Delete "$Base/api/admin/users/$($created.data.id)" $ah).code)"
} else {
  "测试账号 $name 未能通过接口创建，请手工清理"
}
