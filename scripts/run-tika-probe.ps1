# 用项目 fat-jar 里自带的 Tika/PDFBox 复现"解析卡死"：
#   - 从 app.jar 的 BOOT-INF/lib 解出依赖当 classpath（fat-jar 不能直接当 -cp 用）
#   - 跑 scripts/TikaParseProbe.java（30 秒未返回就打工作线程栈）
# 用法： pwsh -File scripts/run-tika-probe.ps1 [-Pdf docs/test-papers/数学知识点.pdf]
param(
  [string]$Pdf = 'docs/test-papers/数学知识点.pdf',
  [string]$Jar = 'app/build/libs/app-0.0.1-SNAPSHOT.jar',
  [string]$Xmx = '512m'
)

$ErrorActionPreference = 'Stop'
$javaHome = 'C:\Users\15799\.jdks\temurin-25.0.4.1'
$java = Join-Path $javaHome 'bin\java.exe'
$jarExe = Join-Path $javaHome 'bin\jar.exe'
$repo = Split-Path -Parent $PSScriptRoot

$jarPath = (Resolve-Path (Join-Path $repo $Jar)).Path
$pdfPath = (Resolve-Path (Join-Path $repo $Pdf)).Path
$probe = Join-Path $repo 'scripts\TikaParseProbe.java'

$work = Join-Path $env:TEMP ("tika-probe-" + (Get-Random))
New-Item -ItemType Directory -Force -Path $work | Out-Null
Push-Location $work
try {
  & $jarExe xf $jarPath 'BOOT-INF/lib'
} finally {
  Pop-Location
}

Write-Host "classpath 目录: $work\BOOT-INF\lib"
& $java "-Xmx$Xmx" -cp "$work\BOOT-INF\lib\*" $probe $pdfPath
Write-Host "exit=$LASTEXITCODE"
