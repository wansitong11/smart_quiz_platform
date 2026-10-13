# =============================================================================
# 共享资料维护权限验证
#
# 规则：共享资料（SHARED）"所有人可读、可下载、可检索、可开考，只有管理员可维护"；
#       私有资料只有归属人可维护；?scope=all 不再给写权限。
#
# 造 3 个账号：管理员 A（创建共享资料）、管理员 B（**非创建者**）、普通用户 C。
# 逐个打接口，断言 404（不该能做）与 200（该能做），并核对夹具没被改动。
#
# 用法：pwsh -File scripts/verify-shared-kb-permission.ps1
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$KbFile = 'docs/test-papers/小学数学知识点汇总（1）.txt',
  [string]$PgContainer = 'my_pgvector',
  [string]$PgDatabase = 'smart-quiz',
  [string]$PgUser = 'postgres'
)

$ErrorActionPreference = 'Continue'
$failures = @()

function Invoke-Api {
  param([string]$Method, [string]$Url, [hashtable]$Headers = @{}, $Body = $null)
  try {
    if ($null -ne $Body) {
      # 注意：字符串体要原样发（再 ConvertTo-Json 会被包成 JSON 字符串字面量，后端报 HttpMessageNotReadable）
      $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
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

function Check {
  param([string]$Label, $Result, [string]$Expect)
  $code = if ($null -ne $Result.code) { $Result.code } else { '200' }
  $msg = if ($Result.message) { ($Result.message -replace '\s+', ' ') } else { '' }
  $ok = ("$code" -eq $Expect)
  if (-not $ok) { $script:failures += "$Label -> $code（期望 $Expect）$msg" }
  "{0,-46} code={1,-6} {2}" -f $Label, $code, $(if ($ok) { 'OK' } else { "!!! 期望 $Expect" })
}

function Ensure-User {
  param([string]$Name, [switch]$Admin)
  $password = 'Shared@12345678'
  $reg = Invoke-Api Post "$Base/api/auth/register" @{} @{ username = $Name; password = $password }
  if ($reg.code -and $reg.code -ne 200) { Write-Error "注册 $Name 失败: $($reg.message)"; exit 1 }
  if ($Admin) {
    PgExec "INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r WHERE u.username='$Name' AND r.code='ROLE_ADMIN' ON CONFLICT DO NOTHING;" | Out-Null
  }
  # 提权后必须重新登录，JWT 里的角色才会更新
  $login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $Name; password = $password }
  if (-not $login.data.accessToken) { Write-Error "$Name 登录失败: $($login.message)"; exit 1 }
  return [pscustomobject]@{
    name = $Name; password = $password
    token = $login.data.accessToken
    header = @{ Authorization = "Bearer $($login.data.accessToken)" }
  }
}

$suffix = Get-Random -Maximum 9999
$adminA = Ensure-User -Name "spAdm$suffix" -Admin
$adminB = Ensure-User -Name "spAdmB$suffix" -Admin   # 非创建者的管理员
$plainC = Ensure-User -Name "spUser$suffix"          # 普通用户
"账号：$($adminA.name)（管理员/创建者）、$($adminB.name)（管理员/非创建者）、$($plainC.name)（普通用户）"
''

# A 上传一份共享资料
$upJson = & curl.exe -s -X POST "$Base/api/knowledgebase/upload?visibility=SHARED" `
  -H "Authorization: Bearer $($adminA.token)" -F "file=@$KbFile" -F 'name=共享权限验证资料'
$up = $upJson | ConvertFrom-Json
$kbId = $up.data.knowledgeBase.id
if (-not $kbId) { Write-Error "上传失败: $upJson"; exit 1 }
"$($adminA.name) 上传共享资料 -> kbId=$kbId（visibility 应为 SHARED：$((Invoke-Api Get "$Base/api/knowledgebase/$kbId" $adminA.header).data.visibility)）"
''

"【普通用户 C：只能读，不能维护】"
Check 'C 列表里能看到这条共享资料' @{ code = (($(Invoke-Api Get "$Base/api/knowledgebase/list" $plainC.header).data | Where-Object { $_.id -eq $kbId }).Count) } '1'
Check 'C 看详情' (Invoke-Api Get "$Base/api/knowledgebase/$kbId" $plainC.header) '200'
Check 'C 看题库（只读）' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions" $plainC.header) '200'
Check 'C 看题库方向' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions/categories" $plainC.header) '200'
Check 'C 看生成状态' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions/generation-status" $plainC.header) '200'
Check 'C 看开考容量' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/interview-capacity?difficulty=mid&mainQuestionCount=3" $plainC.header) '200'
Check 'C 改分类' (Invoke-Api Put "$Base/api/knowledgebase/$kbId/category" $plainC.header '{"categoryCode":""}') '404'
Check 'C 重新向量化' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/revectorize" $plainC.header) '404'
Check 'C 让资料出题' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/questions/generate" $plainC.header @{ questionCount = 1; categoryLimit = 1 }) '404'
Check 'C 手动新增题目' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/questions" $plainC.header @{ category = '测试'; question = '这道题不该被创建' }) '404'
Check 'C 删除共享资料' (Invoke-Api Delete "$Base/api/knowledgebase/$kbId" $plainC.header) '404'
$stillThere = PgExec "SELECT count(*) FROM knowledge_bases WHERE id=$kbId;"
"共享资料仍在 = $stillThere（期望 1）"
if ($stillThere -ne '1') { $failures += '普通用户把共享资料删掉了' }
''

"【管理员 B：不是创建者，照样能维护】"
Check 'B 改分类' (Invoke-Api Put "$Base/api/knowledgebase/$kbId/category" $adminB.header '{"categoryCode":""}') '200'
# 出题要求"已完成向量化"，先等上传触发的向量化跑完（否则会拿到 400 业务错，与权限无关）
$vectorOk = $false
for ($i = 0; $i -lt 30; $i++) {
  $kb = (Invoke-Api Get "$Base/api/knowledgebase/$kbId" $adminB.header).data
  if ("$($kb.vectorStatus)" -eq 'COMPLETED') { $vectorOk = $true; break }
  Start-Sleep -Seconds 5
}
"等待向量化完成 = $vectorOk（$($kb.vectorStatus)）"
Check 'B 让资料出题' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/questions/generate" $adminB.header @{ questionCount = 1; categoryLimit = 1 }) '200'
Check 'B 手动新增题目' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/questions" $adminB.header @{ category = '测试'; question = '管理员新增的题目' }) '200'
# 放最后：重新向量化会把状态置回 PENDING
Check 'B 重新向量化' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/revectorize" $adminB.header) '200'
''

"【回归：私有资料仍然是归属人说了算】"
$privJson = & curl.exe -s -X POST "$Base/api/knowledgebase/upload" `
  -H "Authorization: Bearer $($plainC.token)" -F "file=@$KbFile" -F 'name=C的私有资料'
$priv = $privJson | ConvertFrom-Json
$privId = $priv.data.knowledgeBase.id
"C 上传私有资料 -> kbId=$privId"
Check 'C 改自己私有资料的分类' (Invoke-Api Put "$Base/api/knowledgebase/$privId/category" $plainC.header '{"categoryCode":""}') '200'
Check 'B 改 C 的私有资料分类（管理员也不行）' (Invoke-Api Put "$Base/api/knowledgebase/$privId/category" $adminB.header '{"categoryCode":""}') '404'
Check 'B（切到 scope=all）改 C 的私有资料' (Invoke-Api Put "$Base/api/knowledgebase/$privId/category?scope=all" $adminB.header '{"categoryCode":""}') '404'
Check 'B（切到 scope=all）仍能改共享资料' (Invoke-Api Put "$Base/api/knowledgebase/$kbId/category?scope=all" $adminB.header '{"categoryCode":""}') '200'
''

"【清理】"
"删除共享资料 -> $((Invoke-Api Delete "$Base/api/knowledgebase/$kbId" $adminB.header).code)"
"删除 C 的私有资料 -> $((Invoke-Api Delete "$Base/api/knowledgebase/$privId" $plainC.header).code)"
$names = "'$($adminA.name)','$($adminB.name)','$($plainC.name)'"
PgExec "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ($names));" | Out-Null
PgExec "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE username IN ($names));" | Out-Null
PgExec "DELETE FROM users WHERE username IN ($names);" | Out-Null
"已删除测试账号：$names"
''

if ($failures.Count -eq 0) { '✅ 权限矩阵全部符合预期' } else {
  "❌ 发现 $($failures.Count) 处问题："
  $failures | ForEach-Object { "  - $_" }
}
