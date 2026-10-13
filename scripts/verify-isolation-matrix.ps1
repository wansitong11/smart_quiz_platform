# =============================================================================
# 用户数据隔离 · 全量越权矩阵（第二个用户的 token 打第一个用户的资源）
#
# 目标：把「带 id 的接口」逐个打一遍，确认越权一律 404（而不是 200 / 2001 / 3001 / 6001）。
# 破坏性操作（交卷 / 删除 / 重出题 / 重新向量化）全部打在一个**临时夹具会话**上，
# 不碰真实数据；脚本结束会核对夹具是否还在（在 = 越权被正确拒绝）。
#
# 用法：
#   $env:ADMIN_PASSWORD='<admin 密码>'; pwsh -File scripts/verify-isolation-matrix.ps1
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$AdminUser = 'admin',
  [string]$AdminPassword = $env:ADMIN_PASSWORD,
  [string]$PgContainer = 'my_pgvector',
  [string]$PgDatabase = 'smart-quiz',
  [string]$PgUser = 'postgres'
)

if (-not $AdminPassword) {
  Write-Error '缺少 admin 密码：请通过 $env:ADMIN_PASSWORD 传入'
  exit 1
}

$ErrorActionPreference = 'Continue'
$failures = @()

function Invoke-Api {
  param([string]$Method, [string]$Url, [hashtable]$Headers = @{}, $Body = $null, [switch]$Raw)
  try {
    if ($null -ne $Body) {
      $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
      $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 90
    } else {
      $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $Headers -TimeoutSec 90
    }
    if ($Raw) { return @{ status = $resp.StatusCode; body = $resp.Content } }
    return ($resp.Content | ConvertFrom-Json)
  }
  catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $text = ''
    try { $text = (New-Object System.IO.StreamReader($_.Exception.Response.Content.ReadAsStream())).ReadToEnd() } catch {}
    if ($Raw) { return @{ status = $status; body = $text } }
    if ($text) { try { return ($text | ConvertFrom-Json) } catch {} }
    return [pscustomobject]@{ code = "HTTP_$status"; message = $_.Exception.Message }
  }
}

function Check {
  param([string]$Label, $Result, [string]$Expect = '404')
  $code = if ($null -ne $Result.code) { $Result.code } else { '200' }
  $msg = if ($Result.message) { ($Result.message -replace '\s+', ' ') } else { '' }
  $ok = ($code -eq $Expect)
  if (-not $ok) { $script:failures += "$Label -> $code（期望 $Expect）$msg" }
  "{0,-46} code={1,-6} {2}" -f $Label, $code, $(if ($ok) { 'OK' } else { "!!! 期望 $Expect" })
}

# 下载 / 导出类接口返回的是二进制，失败时后端按项目约定回 HTTP 200 + JSON 错误体，
# 所以这里要读 body 里的业务码，而不是看 HTTP 状态码。
function ProbeBinary {
  param([string]$Method, [string]$Url, [hashtable]$Headers = @{})
  $r = Invoke-Api $Method $Url $Headers -Raw
  if ($r.body) {
    try {
      $j = $r.body | ConvertFrom-Json
      if ($null -ne $j.code) { return [pscustomobject]@{ code = $j.code; message = $j.message } }
    } catch {}
  }
  return [pscustomobject]@{ code = $r.status; message = '二进制响应' }
}

function PgExec {
  param([string]$Sql)
  $out = & docker exec $PgContainer psql -U $PgUser -d $PgDatabase -t -A -c $Sql 2>&1
  return ($out | Out-String).Trim()
}

# ---------- 准备：admin 的资源 + 夹具会话 ----------
$admin = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $AdminUser; password = $AdminPassword }
if (-not $admin.data.accessToken) { Write-Error "admin 登录失败: $($admin.message)"; exit 1 }
$ah = @{ Authorization = "Bearer $($admin.data.accessToken)" }

$paperId = @((Invoke-Api Get "$Base/api/resumes" $ah).data)[0].id
$kbId = @((Invoke-Api Get "$Base/api/knowledgebase/list" $ah).data)[0].id
$quizSessionId = PgExec "SELECT session_id FROM practice_sessions WHERE owner_id=(SELECT id FROM users WHERE username='$AdminUser') AND source_type='QUIZ' ORDER BY id LIMIT 1;"
$kbSessionId = PgExec "SELECT session_id FROM practice_sessions WHERE owner_id=(SELECT id FROM users WHERE username='$AdminUser') ORDER BY id LIMIT 1;"

$fixtureId = 'isofix' + ([guid]::NewGuid().ToString('N').Substring(0, 10))
PgExec "INSERT INTO practice_sessions (session_id, owner_id, status, source_type, total_questions, created_at, quiz_config_json) VALUES ('$fixtureId', (SELECT id FROM users WHERE username='$AdminUser'), 'CREATED', 'QUIZ', 1, now(), '{}');" | Out-Null
$fixtureKbHash = 'isofixkb' + ([guid]::NewGuid().ToString('N').Substring(0, 20))
PgExec "INSERT INTO knowledge_bases (name, original_filename, file_hash, uploaded_at, owner_id, visibility, vector_status) VALUES ('隔离夹具卷', 'fixture.txt', '$fixtureKbHash', now(), (SELECT id FROM users WHERE username='$AdminUser'), 'PRIVATE', 'COMPLETED') RETURNING id;" | Out-Null
$fixtureKbId = PgExec "SELECT id FROM knowledge_bases WHERE file_hash='$fixtureKbHash';"

"admin 资源: paperId=$paperId kbId=$kbId quizSession=$quizSessionId kbSession=$kbSessionId"
"夹具会话(属于 admin): $fixtureId"
"夹具资料(属于 admin): id=$fixtureKbId"
''

# ---------- 攻击者 B ----------
# 用管理员接口建号：/api/auth/register 限流 5 次/小时/IP，反复跑验证会撞限流
$name = 'isoatk' + (Get-Random -Maximum 99999)
$created = Invoke-Api Post "$Base/api/admin/users" $ah @{ username = $name; password = 'Isolate@12345' }
$attackerId = $created.data.id
$login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $name; password = 'Isolate@12345' }
if (-not $login.data.accessToken) { Write-Error "攻击者账号登录失败: $($login.message)"; exit 1 }
$bh = @{ Authorization = "Bearer $($login.data.accessToken)" }
"攻击者 B = $name"
''

"【试卷线】"
Check 'B 取 admin 试卷详情' (Invoke-Api Get "$Base/api/resumes/$paperId/detail" $bh)
Check 'B 重切 admin 试卷' (Invoke-Api Post "$Base/api/resumes/$paperId/reanalyze" $bh)
Check 'B 用 admin 试卷开考' (Invoke-Api Post "$Base/api/resumes/$paperId/attempts" $bh)
Check 'B 导出 admin 试卷 PDF' (ProbeBinary Get "$Base/api/resumes/$paperId/export" $bh)
''

"【测验 / 会话线】"
Check 'B 取 admin 测验会话' (Invoke-Api Get "$Base/api/quiz/sessions/$quizSessionId" $bh)
Check 'B 取 admin 测验报告' (Invoke-Api Get "$Base/api/quiz/sessions/$quizSessionId/report" $bh)
Check 'B 取 admin 测验详情' (Invoke-Api Get "$Base/api/quiz/sessions/$quizSessionId/details" $bh)
Check 'B 导出 admin 测验 PDF' (Invoke-Api Get "$Base/api/quiz/sessions/$quizSessionId/export" $bh)
Check 'B 对 admin 会话再测一次' (Invoke-Api Post "$Base/api/quiz/sessions/$quizSessionId/retry" $bh)
Check 'B 交 admin 会话的卷' (Invoke-Api Post "$Base/api/quiz/sessions/$fixtureId/submit" $bh @{ answers = @(@{ questionIndex = 0; answer = 'x' }) })
Check 'B 删 admin 的测验会话' (Invoke-Api Delete "$Base/api/quiz/sessions/$fixtureId" $bh)
Check 'B 取 admin 面试会话' (Invoke-Api Get "$Base/api/interview/sessions/$kbSessionId" $bh)
Check 'B 取 admin 面试详情' (Invoke-Api Get "$Base/api/interview/sessions/$kbSessionId/details" $bh)
Check 'B 取 admin 面试报告' (Invoke-Api Get "$Base/api/interview/sessions/$kbSessionId/report" $bh)
Check 'B 取 admin 面试当前题' (Invoke-Api Get "$Base/api/interview/sessions/$kbSessionId/question" $bh)
Check 'B 导出 admin 面试 PDF' (ProbeBinary Get "$Base/api/interview/sessions/$kbSessionId/export" $bh)
Check 'B 删 admin 的面试会话' (Invoke-Api Delete "$Base/api/interview/sessions/$fixtureId" $bh)
''

"【资料 / 题库线】"
Check 'B 取 admin 资料' (Invoke-Api Get "$Base/api/knowledgebase/$kbId" $bh)
Check 'B 列 admin 题库' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions" $bh)
Check 'B 列 admin 题库方向' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions/categories" $bh)
Check 'B 查 admin 出题状态' (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions/generation-status" $bh)
Check 'B 让 admin 资料重新出题' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/questions/generate" $bh @{ questionCount = 1; categoryLimit = 1 })
Check 'B 改 admin 资料分类' (Invoke-Api Put "$Base/api/knowledgebase/$kbId/category" $bh '{"categoryCode":""}')
Check 'B 让 admin 资料重新向量化' (Invoke-Api Post "$Base/api/knowledgebase/$kbId/revectorize" $bh)
Check 'B 下载 admin 资料文件' (ProbeBinary Get "$Base/api/knowledgebase/$kbId/download" $bh)
Check 'B 删 admin 资料' (Invoke-Api Delete "$Base/api/knowledgebase/$fixtureKbId" $bh)
''

"【RAG 问答线】"
$ragSessions = (Invoke-Api Get "$Base/api/rag-chat/sessions" $bh).data
"B 的问答会话数 = $(@($ragSessions).Count)（期望 0）"
if (@($ragSessions).Count -ne 0) { $failures += 'B 能看到别人的问答会话' }
''

"【夹具完整性】"
$left = PgExec "SELECT count(*) FROM practice_sessions WHERE session_id='$fixtureId';"
"夹具会话剩余 = $left（期望 1，说明越权删除 / 交卷全部被拒）"
if ($left -ne '1') { $failures += "夹具会话被越权修改或删除（剩余 $left）" }
$afterKb = PgExec "SELECT count(*) FROM knowledge_bases WHERE id=$kbId;"
"admin 资料 $kbId 仍存在 = $afterKb（期望 1）"
if ($afterKb -ne '1') { $failures += "admin 资料被越权删除" }
$fixtureKbLeft = PgExec "SELECT count(*) FROM knowledge_bases WHERE id=$fixtureKbId;"
"夹具资料剩余 = $fixtureKbLeft（期望 1，说明越权删除被拒）"
if ($fixtureKbLeft -ne '1') { $failures += "夹具资料被越权删除" }
''

if ($failures.Count -eq 0) {
  '✅ 全部越权请求都被拒绝（404），夹具未被改动'
} else {
  "❌ 发现 $($failures.Count) 处问题："
  $failures | ForEach-Object { "  - $_" }
}

# ---------- 清理本次夹具与测试账号 ----------
PgExec "DELETE FROM practice_sessions WHERE session_id='$fixtureId';" | Out-Null
PgExec "DELETE FROM knowledge_bases WHERE id=$fixtureKbId;" | Out-Null
if ($attackerId) {
  Invoke-Api Delete "$Base/api/admin/users/$attackerId" $ah | Out-Null
}
"已清理夹具会话 $fixtureId、夹具资料 $fixtureKbId、测试账号 $name"
