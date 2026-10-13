# =============================================================================
# 上传切题 + 重新切题 端到端验证（后台切题线程建会话时不能依赖登录上下文）
#
# 复现路径：PaperExtractionStreamConsumer -> createSessionFromQuestions -> saveSession
#   上传新试卷 / 重新切题 都会走这里；后台线程没有登录上下文，归属人应沿用试卷归属人。
#
# 用法：pwsh -File scripts/verify-paper-cut.ps1 [-UploadFile docs/test-papers/语文-测试卷.txt]
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$UploadFile = 'docs/test-papers/语文-测试卷.txt',
  [int]$TimeoutSeconds = 420,
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

# 注册一个测试用户（验证实例用独立 Redis 库，限流计数不与日常实例共享）
$name = 'cut' + (Get-Random -Maximum 99999)
$password = 'Cut@12345678'
$reg = Invoke-Api Post "$Base/api/auth/register" @{} @{ username = $name; password = $password }
if ($reg.code -and $reg.code -ne 200) { Write-Error "注册测试用户失败: code=$($reg.code) $($reg.message)"; exit 1 }
$login = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $name; password = $password }
if (-not $login.data.accessToken) { Write-Error "测试用户登录失败: $($login.message)"; exit 1 }
$h = @{ Authorization = "Bearer $($login.data.accessToken)" }
$uid = PgExec "SELECT id FROM users WHERE username='$name';"
"测试用户 = $name (id=$uid)"
''

function Wait-Analysis {
  param([int]$PaperId, [string]$Stage)
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  $last = ''
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $detail = (Invoke-Api Get "$Base/api/resumes/$PaperId/detail" $h).data
    $status = $detail.analysisStatus
    $count = @($detail.analyses).Count
    if ("$status|$count" -ne $last) {
      "  [$((Get-Date).ToString('HH:mm:ss'))] $Stage analysisStatus=$status 切题记录=$count"
      $last = "$status|$count"
    }
    if ($status -eq 'COMPLETED' -or $status -eq 'FAILED') { return $detail }
  }
  return $null
}

# ---------- 1. 上传试卷（首次切题） ----------
"【1. 上传试卷 → 首次切题】"
$upJson = & curl.exe -s -X POST "$Base/api/resumes/upload" -H "Authorization: Bearer $($login.data.accessToken)" -F "file=@$UploadFile"
$up = $upJson | ConvertFrom-Json
$paperId = $up.data.id
if (-not $paperId) { $paperId = $up.data.resume.id }
"上传返回: id=$paperId message=$($up.message)"
if (-not $paperId) { Write-Error '上传未返回试卷 id'; exit 1 }

$detail = Wait-Analysis -PaperId $paperId -Stage '首次切题'
if ($null -eq $detail) { "⌛ 超时未结束"; exit 1 }
if ($detail.analysisStatus -eq 'COMPLETED') {
  $a = @($detail.analyses) | Select-Object -First 1
  "✅ 首次切题成功: 题数=$($a.questionCount) 总分=$($a.totalScore) 会话=$($a.practiceSessionId)"
} else {
  "❌ 首次切题失败: $($detail.analysisError)"
}

# 切题顺带建的会话，归属人必须是上传者本人
$sessionOwner = PgExec "SELECT owner_id FROM practice_sessions WHERE session_id='$($a.practiceSessionId)';"
"   切题所建会话的 owner_id=$sessionOwner（期望 $uid）"
if ($sessionOwner -ne $uid) { "❌ 会话归属不对" }
''

# ---------- 2. 重新切题 ----------
"【2. 重新切题】"
$r = Invoke-Api Post "$Base/api/resumes/$paperId/reanalyze" $h
if ($r.code -and $r.code -ne 200) { Write-Error "触发重新切题失败: code=$($r.code) $($r.message)"; exit 1 }
$detail2 = Wait-Analysis -PaperId $paperId -Stage '重新切题'
if ($null -eq $detail2) { "⌛ 超时未结束"; exit 1 }
if ($detail2.analysisStatus -eq 'COMPLETED') {
  $a2 = @($detail2.analyses) | Select-Object -First 1
  "✅ 重新切题成功: 题数=$($a2.questionCount) 总分=$($a2.totalScore) 会话=$($a2.practiceSessionId)"
  $owner2 = PgExec "SELECT owner_id FROM practice_sessions WHERE session_id='$($a2.practiceSessionId)';"
  "   新会话 owner_id=$owner2（期望 $uid）"
} else {
  "❌ 重新切题失败: $($detail2.analysisError)"
}
''

"【清理】"
"删除测试试卷 id=$paperId -> $((Invoke-Api Delete "$Base/api/resumes/$paperId" $h).code)（顺带清切题记录、练习会话与存储文件）"
"测试账号 $name 需管理员删除：/api/admin/users/$uid（或 DELETE FROM users WHERE username='$name'）"
