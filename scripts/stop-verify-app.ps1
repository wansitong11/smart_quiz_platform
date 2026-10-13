# 停掉验证用的后端实例（8081），并清理临时编译日志。
# 注意：只按端口杀会漏掉「持有日志句柄的 gradlew 包装进程」，导致下一次启动静默复用旧实例，
# 所以这里把 bootRun 的 java 进程、gradlew 启动器和包装 powershell 一起收掉。
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

$logPath = Join-Path $repo 'build-compile.log'
if (Test-Path -LiteralPath $logPath) {
  Remove-Item -LiteralPath $logPath -Force
  "已删除 $logPath"
}

function Stop-ByCommandLine {
  param([string]$Pattern)
  $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like $Pattern -and $_.ProcessId -ne $PID }
  foreach ($p in $procs) {
    "停止进程 $($p.ProcessId)（$Pattern）"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
  }
}

$conns = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }

Stop-ByCommandLine '*:app:bootRun*'
Stop-ByCommandLine '*smart_quiz_platform*verify-iso2.log*'

Start-Sleep -Seconds 6

$still = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
if ($still) {
  "8081 仍在监听（pid=$($still[0].OwningProcess)）"
} else {
  '8081 已停止，可以重新启动验证实例'
}
