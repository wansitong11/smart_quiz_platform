# =============================================================================
# 知识库出题验证：① 追加不覆盖 ② 难度/语体跟着资料学段（小学资料出小学题）
#
# 流程：注册测试用户 → 上传小学知识库 → 等向量化 → 生成 3 道题 → 再生成 3 道题
#   - 第二次必须"追加"（题库只增不减，且提示词里带上已有题避免重复）
#   - 打印生成的题目，人工核对是不是小学难度/小学语体
#
# 用法：pwsh -File scripts/verify-kb-question-append.ps1
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$KbFile = 'docs/test-papers/小学数学知识点汇总（1）.txt',
  [int]$PerRound = 3,
  [int]$TimeoutSeconds = 480,
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
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 180
    }
    return Invoke-RestMethod -Uri $Url -Method $Method -Headers $Headers -TimeoutSec 180
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

$name = 'kbgen' + (Get-Random -Maximum 99999)
$password = 'Kbgen@12345678'
$reg = Invoke-Api Post "$Base/api/auth/register" @{} @{ username = $name; password = $password }
if ($reg.code -and $reg.code -ne 200) { Write-Error "注册测试用户失败: code=$($reg.code) $($reg.message)"; exit 1 }
$login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $name; password = $password }
if (-not $login.data.accessToken) { Write-Error "测试用户登录失败: $($login.message)"; exit 1 }
$h = @{ Authorization = "Bearer $($login.data.accessToken)" }
$uid = PgExec "SELECT id FROM users WHERE username='$name';"
"测试用户 = $name (id=$uid)"
''

# ---------- 1. 上传小学知识库并等向量化 ----------
"【1. 上传知识库】$KbFile"
$upJson = & curl.exe -s -X POST "$Base/api/knowledgebase/upload" -H "Authorization: Bearer $($login.data.accessToken)" -F "file=@$KbFile" -F 'name=小学数学知识点'
$up = $upJson | ConvertFrom-Json
$kbId = $up.data.knowledgeBase.id
if (-not $kbId) { Write-Error "上传失败: $upJson"; exit 1 }
"知识库 id=$kbId"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$vectorStatus = ''
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 10
  $kb = (Invoke-Api Get "$Base/api/knowledgebase/$kbId" $h).data
  if ("$($kb.vectorStatus)" -ne $vectorStatus) {
    $vectorStatus = "$($kb.vectorStatus)"
    "  [$((Get-Date).ToString('HH:mm:ss'))] vectorStatus=$vectorStatus"
  }
  if ($vectorStatus -eq 'COMPLETED' -or $vectorStatus -eq 'FAILED') { break }
}
if ($vectorStatus -ne 'COMPLETED') { Write-Error "向量化未完成: $vectorStatus"; exit 1 }
''

# ---------- 2. 生成两轮 ----------
function Generate-Round {
  param([int]$Round)
  "【第 $Round 轮生成：$PerRound 道 / 中等难度】"
  $before = @((Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions" $h).data)
  "生成前题库 = $($before.Count) 道"
  $r = Invoke-Api Post "$Base/api/knowledgebase/$kbId/questions/generate" $h @{
    difficulty = 'mid'; questionCount = $PerRound; followUpCount = 0; categoryLimit = 2
  }
  if ($r.code -and $r.code -ne 200) { Write-Error "触发生成失败: code=$($r.code) $($r.message)"; exit 1 }

  $dl = (Get-Date).AddSeconds($TimeoutSeconds)
  $status = ''
  while ((Get-Date) -lt $dl) {
    Start-Sleep -Seconds 8
    $st = (Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions/generation-status" $h).data
    if ("$($st.questionGenStatus)" -ne $status) {
      $status = "$($st.questionGenStatus)"
      "  [$((Get-Date).ToString('HH:mm:ss'))] questionGenStatus=$status"
    }
    if ($status -eq 'COMPLETED' -or $status -eq 'FAILED') { break }
  }
  if ($status -ne 'COMPLETED') { Write-Error "生成未完成: $status $($st.questionGenError)"; exit 1 }
  "  完成消息: $($st.message)"

  $after = @((Invoke-Api Get "$Base/api/knowledgebase/$kbId/questions" $h).data)
  "生成后题库 = $($after.Count) 道（原有 $($before.Count) 道）"
  ''
  return @{ before = $before.Count; after = $after.Count; message = $st.message; items = $after }
}

$r1 = Generate-Round -Round 1
$r2 = Generate-Round -Round 2

"【结论】"
if ($r1.after -eq $r1.before) { "❌ 第一轮没有新增题目" } else { "✅ 第一轮新增 $($r1.after - $r1.before) 道" }
if ($r2.after -gt $r1.after) { "✅ 第二轮是追加：$($r1.after) → $($r2.after) 道，旧题没被删" }
else { "❌ 第二轮没有追加（题库 $($r1.after) → $($r2.after)，可能覆盖了旧题）" }
if ($r2.message -match '保留') { "✅ 完成消息体现了追加语义：$($r2.message)" }
''

"【题库内容（核对是否小学难度/小学语体）】"
$r2.items | ForEach-Object {
  $stem = ([string]$_.question -replace '\s+', ' ')
  if ($stem.Length -gt 50) { $stem = $stem.Substring(0, 50) + '…' }
  "  id=$($_.id) 难度=$($_.difficulty) 题型=$($_.questionType) 板块=$($_.category) 分值=$($_.fullScore) | $stem"
}
''

"【清理】删除测试知识库 id=$kbId -> $((Invoke-Api Delete "$Base/api/knowledgebase/$kbId" $h).code)"
"测试账号 $name 需管理员删除（id=$uid）或执行：DELETE FROM users WHERE username='$name';"
