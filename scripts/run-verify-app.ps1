# 启动一个用于验证的后端实例（8081 + 独立 Redis 库），日志写入 verify-iso2.log
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$env:JAVA_HOME = 'C:\Users\15799\.jdks\temurin-25.0.4.1'
$env:REDIS_DATABASE = '1'

$log = Join-Path $repo 'verify-iso2.log'
if (Test-Path $log) { Remove-Item -LiteralPath $log -Force }

# 先停掉上次遗留的验证实例，避免端口占用
$existing = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
foreach ($conn in $existing) {
  Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
}
if ($existing) { Start-Sleep -Seconds 8 }

$cmd = '& .\gradlew.bat :app:bootRun --args=--server.port=8081 *> verify-iso2.log'
Start-Process -FilePath 'powershell.exe' -WorkingDirectory $repo -WindowStyle Hidden `
  -ArgumentList @('-NoProfile', '-Command', $cmd) | Out-Null

Start-Sleep -Seconds 215

$listen = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
if (-not $listen) {
  'APP_NOT_UP'
  Select-String -Path $log -Pattern 'ERROR|Caused by' | Select-Object -Last 8 |
    ForEach-Object { $_.Line.Trim() }
  exit 1
}
"APP_UP pid=$($listen[0].OwningProcess)"
Select-String -Path $log -Pattern 'Successfully applied|Migrating schema' | Select-Object -Last 4 |
  ForEach-Object { $_.Line.Trim() }
