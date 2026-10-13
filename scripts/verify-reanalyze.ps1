# =============================================================================
# 重新切题（reanalyze）端到端验证
#
# 复现并回归「切题失败：登录状态无效」：切题在后台线程跑，后台线程没有登录上下文，
# 建会话那一步不能再依赖当前登录用户（应沿用试卷归属人）。
#
# 用法：$env:ADMIN_PASSWORD='<admin 密码>'; pwsh -File scripts/verify-reanalyze.ps1 [-PaperId 4]
# =============================================================================

param(
  [string]$Base = 'http://localhost:8081',
  [string]$AdminUser = 'admin',
  [string]$AdminPassword = $env:ADMIN_PASSWORD,
  [int]$PaperId = 0,
  [int]$TimeoutSeconds = 420
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
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $text = ''
    try { $text = (New-Object System.IO.StreamReader($_.Exception.Response.Content.ReadAsStream())).ReadToEnd() } catch {}
    if ($text) { try { return ($text | ConvertFrom-Json) } catch {} }
    return [pscustomobject]@{ code = "HTTP_$status"; message = $_.Exception.Message }
  }
}

$admin = Invoke-Api Post "$Base/api/auth/login" @{} @{ username = $AdminUser; password = $AdminPassword }
if (-not $admin.data.accessToken) { Write-Error "admin 登录失败: $($admin.message)"; exit 1 }
$ah = @{ Authorization = "Bearer $($admin.data.accessToken)" }

if ($PaperId -le 0) {
  $PaperId = @((Invoke-Api Get "$Base/api/resumes" $ah).data)[0].id
}
$before = (Invoke-Api Get "$Base/api/resumes/$PaperId/detail" $ah).data
"目标试卷: id=$PaperId filename=$($before.filename) 当前状态=$($before.analysisStatus) 已有切题记录=$(@($before.analyses).Count)"
''

$r = Invoke-Api Post "$Base/api/resumes/$PaperId/reanalyze" $ah
if ($r.code -and $r.code -ne 200) { Write-Error "触发重新切题失败: code=$($r.code) $($r.message)"; exit 1 }
'已触发重新切题，等待后台完成（切题要调用大模型，通常 1~3 分钟）...'
''

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$last = ''
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 10
  $detail = (Invoke-Api Get "$Base/api/resumes/$PaperId/detail" $ah).data
  $status = $detail.analysisStatus
  $analyses = @($detail.analyses)
  if ("$status|$($analyses.Count)" -ne $last) {
    "  [$((Get-Date).ToString('HH:mm:ss'))] analysisStatus=$status 切题记录=$($analyses.Count)"
    $last = "$status|$($analyses.Count)"
  }
  if ($status -eq 'COMPLETED' -or $status -eq 'FAILED') {
    ''
    if ($status -eq 'COMPLETED') {
      $latest = $analyses | Select-Object -First 1
      "✅ 重新切题成功: 题数=$($latest.questionCount) 总分=$($latest.totalScore) 会话=$($latest.practiceSessionId)"
      "   错误信息=$($detail.analysisError)"
    } else {
      "❌ 重新切题失败: $($detail.analysisError)"
    }
    exit 0
  }
}

"⌛ 超时（${TimeoutSeconds}s）仍未结束，最后状态=$last"
