# SSH ProxyCommand 用的 SOCKS5 桥接：把 stdin/stdout 和 SOCKS5 代理里的目标连接对接起来。
# 用途：本机到阿里云香港某些 IP 段的路由不通，借本机已运行的 v2rayN（127.0.0.1:10808）绕过去。
# 用法（由 ssh 自动调用，不手工执行）：
#   ssh -o ProxyCommand="powershell -NoProfile -ExecutionPolicy Bypass -File socks-bridge.ps1 %h %p" ...

param(
  [Parameter(Mandatory = $true)][string]$Target,
  [Parameter(Mandatory = $true)][int]$Port,
  [string]$ProxyHost = '127.0.0.1',
  [int]$ProxyPort = 10808
)

$ErrorActionPreference = 'Stop'

try {
  $client = New-Object System.Net.Sockets.TcpClient
  $client.Connect($ProxyHost, $ProxyPort)
  $stream = $client.GetStream()

  # 1) 打招呼：SOCKS5 + 无认证
  $stream.Write([byte[]]@(5, 1, 0), 0, 3)
  $greeting = New-Object byte[] 2
  [void]$stream.Read($greeting, 0, 2)
  if ($greeting[0] -ne 5 -or $greeting[1] -ne 0) { throw "SOCKS5 握手失败" }

  # 2) CONNECT 请求（用域名形式传 IP）
  $addr = [System.Text.Encoding]::ASCII.GetBytes($Target)
  $req = New-Object byte[] (7 + $addr.Length)
  $req[0] = 5; $req[1] = 1; $req[2] = 0; $req[3] = 3; $req[4] = [byte]$addr.Length
  [Array]::Copy($addr, 0, $req, 5, $addr.Length)
  $req[5 + $addr.Length] = [byte](($Port -shr 8) -band 0xFF)
  $req[6 + $addr.Length] = [byte]($Port -band 0xFF)
  $stream.Write($req, 0, $req.Length)

  # 3) 读响应
  $reply = New-Object byte[] 4
  $read = $stream.Read($reply, 0, 4)
  if ($read -lt 4 -or $reply[1] -ne 0) { throw "SOCKS5 CONNECT 被拒绝 (code=$($reply[1]))" }

  switch ($reply[3]) {
    1 { $skip = 4 }
    4 { $skip = 16 }
    3 {
      $lenBuf = New-Object byte[] 1
      [void]$stream.Read($lenBuf, 0, 1)
      $skip = $lenBuf[0]
    }
    default { throw "SOCKS5 返回了未知地址类型" }
  }
  $skipBuf = New-Object byte[] ($skip + 2)
  [void]$stream.Read($skipBuf, 0, $skipBuf.Length)

  # 4) 双向转发
  $stdin = [Console]::OpenStandardInput()
  $stdout = [Console]::OpenStandardOutput()
  $toRemote = $stdin.CopyToAsync($stream)
  $toLocal = $stream.CopyToAsync($stdout)
  [void][System.Threading.Tasks.Task]::WaitAny(@($toRemote, $toLocal))
}
catch {
  [Console]::Error.WriteLine("socks-bridge: $($_.Exception.Message)")
  exit 1
}
finally {
  if ($stream) { $stream.Dispose() }
  if ($client) { $client.Dispose() }
}
