#Requires -Version 5.1
<#
.SYNOPSIS
    DSH Remote 一键连接：起桥接 / 开隧道 / 签 Cookie，打印地址与凭据，并打开配对二维码页。

.DESCRIPTION
    普通用户双击 connect.cmd。

    实际工作由仓库自带的 bin/dsh-remote.mjs 完成（和 DSH 插件是同一份代码，离线可用）：
      1. 自动发现 DSH 宿主端口（宿主每次重启会换端口）
      2. 起桥接并把 Host 改写为固定 authority，Cookie 因此不会失效
      3. 有 ngrok 用 ngrok，否则用 cloudflared（缺了就自动下载并验签）
      4. 签发 30 天 Cookie，打印地址 + Cookie
      5. 打开浏览器里的配对页二维码，手机扫码即连

    窗口关掉 = 断开手机。
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Say($text, $color = 'White') { Write-Host $text -ForegroundColor $color }

function Find-Node {
    foreach ($c in @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'),
        (Join-Path $env:ProgramFiles 'nodejs\node.exe')
    )) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

Say ''
Say '============================  DSH Remote 一键连接  ============================'

$node = Find-Node
if (-not $node) {
    Say '找不到 node。请先安装 Node.js，或先打开一次 DSH 桌面端（它自带 node）。' 'Red'
    exit 1
}

$cli = Join-Path $Here 'bin\dsh-remote.mjs'
if (-not (Test-Path $cli)) {
    Say '缺少 bin\dsh-remote.mjs（应与本脚本在同一目录）' 'Red'
    exit 1
}

# start --open: 打印地址 + Cookie，然后打开 /_dsh-remote/pair 的二维码页；进程会一直运行。
& $node $cli start --open

# 走到这里说明 start 退出了（Ctrl+C 或出错）。
Say ''
Say '桥接已停止。按 Ctrl+C 或直接关闭窗口。' 'DarkGray'
