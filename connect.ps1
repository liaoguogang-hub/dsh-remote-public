#Requires -Version 5.1
<#
.SYNOPSIS
    DSH Remote 一键连接：起桥接 / 开隧道 / 签 Cookie，打印地址，并打开配对二维码页。

.DESCRIPTION
    普通用户双击 connect.cmd 即可。工作由插件代码完成，按以下顺序找它：

      1. 同目录 bin\dsh-remote.mjs（仓库自带，离线可用）
      2. 已安装的全局 dsh-remote 命令
      3. npx dsh-remote-hub@latest（需要网络，第一次会缓存）

    插件做四件事：发现 DSH 端口 → 起桥接（Host 改写）→ 开隧道或复用已有隧道 → 签 30 天 Cookie，
    然后打印局域网/公网地址与 Cookie，并打开 /_dsh-remote/pair 的二维码页。

    自建中转（如阿里云）可以这样传：
        $env:DSH_REMOTE_PUBLIC_URL="https://你的中转地址"
        .\connect.cmd
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
if (Test-Path $cli) {
    & $node $cli start --open
    Say ''
    Say '桥接已停止。按任意键关闭窗口。' 'DarkGray'
    return
}

# 仓库里没有 bin/：退回到已安装的插件命令，或临时用 npx 拉取（仅首次需要网络）。
$global = Get-Command dsh-remote -ErrorAction SilentlyContinue
if ($global) {
    & $global.Source start --open
    Say ''
    Say '桥接已停止。按任意键关闭窗口。' 'DarkGray'
    return
}

$npx = Get-Command npx -ErrorAction SilentlyContinue
if ($npx) {
    Say '仓库里没有 bin\dsh-remote.mjs，改用 npx 运行已发布的插件…' 'Yellow'
    & $npx.Source --yes dsh-remote-hub@latest start --open
    Say ''
    Say '桥接已停止。按任意键关闭窗口。' 'DarkGray'
    return
}

Say '找不到插件代码：本目录没有 bin\dsh-remote.mjs，也没有安装 dsh-remote。' 'Red'
Say '请先安装插件： dsh plugin --profile desktop add dsh-remote-hub' 'Yellow'
Say '或在完整仓库目录里运行本脚本（仓库根目录应包含 bin\ 与 lib\）。' 'Yellow'
