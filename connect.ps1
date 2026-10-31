<#
.SYNOPSIS
    DSH Remote 一键连接：起桥接 → 签 Cookie → 尽量开公网隧道 → 打印「地址 + Cookie」。

.DESCRIPTION
    普通用户双击同目录的 connect.cmd。

      1. 找 node（优先 DSH 自带运行时，其次 PATH）
      2. 起桥接 lan-proxy.mjs（把 Host 改写成 127.0.0.1:1815，并自动发现 DSH 端口）
      3. 签发 30 天 Cookie（本机 ~/.dsh/.credentials.yaml 里的签名密钥）
      4. 公网隧道：有 ngrok / cloudflared 且其服务可达时才会给公网地址；
         否则明确说明「用局域网地址即可」，不假装成功。

    会打印并自动复制：局域网地址 + Cookie（以及公网地址，如果拿得到）。
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$DshHome = Join-Path $env:USERPROFILE '.dsh'
$Work = Join-Path $env:LOCALAPPDATA 'dsh-remote'
$Authority = '127.0.0.1:1815'
$BridgePort = 6300
$BridgeLog = Join-Path $Work 'bridge.log'
$TunnelLog = Join-Path $Work 'tunnel.log'
$InfoFile = Join-Path $Here 'connect-info.txt'

New-Item -ItemType Directory -Force -Path $Work | Out-Null

function Say($text, $color = 'White') { Write-Host $text -ForegroundColor $color }
function Step($text) { Say "  -> $text" 'Cyan' }
function Warn($text) { Say "  ! $text" 'Yellow' }

function Find-Node {
    foreach ($c in @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'),
        (Join-Path $env:ProgramFiles 'nodejs\node.exe')
    )) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Find-Cloudflared {
    $names = @('cloudflared.exe', 'cloudflared-windows-amd64.exe')
    $dirs = @(
        $Here,
        (Join-Path $Here 'tools'),
        $Work,
        (Join-Path $DshHome 'bin'),
        (Join-Path $DshHome 'cloudflared'),
        (Join-Path $env:USERPROFILE 'Documents\DSH\DshRemote\tools'),
        (Join-Path $env:USERPROFILE 'Documents\DSH\.dsh-apk-research\tools')
    )
    foreach ($d in $dirs) {
        if (-not (Test-Path $d)) { continue }
        foreach ($n in $names) {
            $p = Join-Path $d $n
            if (Test-Path $p) { return $p }
        }
    }
    $cmd = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Find-Ngrok {
    $cmd = Get-Command ngrok -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($d in @(
        $Here,
        (Join-Path $Here 'tools'),
        $Work,
        (Join-Path $env:LOCALAPPDATA 'ngrok'),
        (Join-Path $env:USERPROFILE 'Documents\DSH\DshRemote\tools'),
        (Join-Path $env:USERPROFILE 'Documents\DSH\.dsh-apk-research\tools')
    )) {
        if (-not (Test-Path $d)) { continue }
        foreach ($n in @('ngrok.exe')) {
            $p = Join-Path $d $n
            if (Test-Path $p) { return $p }
        }
    }
    return $null
}

function Test-Http([string]$url, [int]$timeoutSec = 6) {
    try {
        Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $timeoutSec -Method Head -ErrorAction Stop | Out-Null
        return $true
    } catch {
        if ("$($_.Exception.Message)" -match '\b4\d\d\b') { return $true }
        return $false
    }
}

<#
  A downloaded cloudflared is only trusted when its Authenticode signature is valid and the
  signer is Cloudflare, Inc. A mirror can return anything; the signature cannot be forged
  without the Cloudflare private key, so this check is what makes mirror downloads safe.
#>
function Test-CloudflaredSignature([string]$path) {
    try {
        $sig = Get-AuthenticodeSignature -FilePath $path
        if ($sig.Status -ne 'Valid') { return $false }
        $subject = "$($sig.SignerCertificate.Subject)"
        return ($subject -like '*Cloudflare*' -and $subject -like '*Inc*')
    } catch { return $false }
}

function Download-Cloudflared {
    $dest = Join-Path $Work 'cloudflared.exe'
    # 镜像优先：国内直连 GitHub 下 52 MB 基本必超时；镜像拿到的是同一份签名文件，
    # 所以用签名校验而不是哈希来判定真伪。
    $urls = @(
        'https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe',
        'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe',
        'https://gh-proxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe'
    )
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    foreach ($u in $urls) {
        Step "下载 cloudflared：$u"
        try {
            if ($curl) {
                & $curl.Source -L --fail --retry 1 --connect-timeout 15 --max-time 240 -o $dest $u 2>$null
            } else {
                Invoke-WebRequest -Uri $u -OutFile $dest -UseBasicParsing -TimeoutSec 240
            }
            if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 20MB)) {
                if (Test-CloudflaredSignature $dest) {
                    Step '下载完成，Authenticode 签名校验通过（Cloudflare, Inc.）'
                    return $dest
                }
                Warn '下载到的文件签名无效（或不是 Cloudflare 签发），已丢弃，换下一个源'
            }
        } catch {}
        if (Test-Path $dest) { Remove-Item $dest -Force -ErrorAction SilentlyContinue }
    }
    return $null
}

<#
  ngrok 的 authtoken 与固定域名都放在用户自己的 ngrok 配置文件里；这里只做只读查询，
  绝不把 token 写进日志或 connect-info.txt。
#>
function Get-NgrokConfigPath {
    foreach ($p in @((Join-Path $env:LOCALAPPDATA 'ngrok\ngrok.yml'), (Join-Path $env:USERPROFILE '.ngrok2\ngrok.yml'))) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Test-NgrokAuthtoken {
    $cfg = Get-NgrokConfigPath
    if (-not $cfg) { return $false }
    return (Select-String -Path $cfg -Pattern "authtoken:\s*\S+" -Quiet)
}

<#
  Ask the ngrok agent what it is currently exposing. Robust against log formats and it also
  lets us reuse a tunnel the user already has running instead of starting a second one.
#>
function Get-NgrokTunnelUrl([int]$port = 4040) {
    try {
        $api = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/api/tunnels" -f $port) -TimeoutSec 4 -ErrorAction Stop
        foreach ($t in $api.tunnels) {
            if ("$($t.config.addr)" -match ":$BridgePort$") { return $t.public_url }
        }
    } catch {}
    return $null
}

function Wait-ForNgrokUrl([int]$timeoutSeconds = 45) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $u = Get-NgrokTunnelUrl
        if ($u) { return $u }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Wait-ForTunnelUrl([int]$timeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($f in @($TunnelLog, "$TunnelLog.out")) {
            if (-not (Test-Path $f)) { continue }
            $text = Get-Content $f -Raw -ErrorAction SilentlyContinue
            if (-not $text) { continue }
            $m = [regex]::Match($text, 'https://[a-z0-9][a-z0-9-]*\.(?:trycloudflare\.com|ngrok-free\.(?:dev|app)|ngrok\.(?:io|app))')
            if ($m.Success) { return $m.Value }
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

Say ''
Say '============================  DSH Remote 一键连接  ============================'

# ------------------------------------------------------------------ 0. 前置
$credFile = Join-Path $DshHome '.credentials.yaml'
if (-not (Test-Path $credFile)) {
    Say "找不到 DSH 凭据文件：$credFile" 'Red'
    Say '请先打开一次 DSH 桌面端并正常使用过，再运行本脚本。' 'Yellow'
    exit 1
}
$node = Find-Node
if (-not $node) {
    Say '找不到 node。请先打开一次 DSH 桌面端（它自带 node），或安装 Node.js。' 'Red'
    exit 1
}
$bridgeScript = Join-Path $Here 'lan-proxy.mjs'
$mintScript = Join-Path $Here 'mint.mjs'
foreach ($f in @($bridgeScript, $mintScript)) {
    if (-not (Test-Path $f)) { Say "缺少文件：$f（应与本脚本在同一目录）" 'Red'; exit 1 }
}

# ------------------------------------------------------------------ 1. 桥接
$bridgeProc = $null
$existing = Get-NetTCPConnection -LocalPort $BridgePort -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Step "端口 $BridgePort 上已有桥接在跑，直接复用（退出时不会关它）"
} else {
    Step "启动桥接 127.0.0.1:$BridgePort -> DSH（自动发现端口）"
    if (Test-Path $BridgeLog) { Remove-Item $BridgeLog -Force -ErrorAction SilentlyContinue }
    $bridgeProc = Start-Process -FilePath $node `
        -ArgumentList @($bridgeScript, "$BridgePort", "$Authority") `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $BridgeLog -RedirectStandardError "$BridgeLog.err"
    Start-Sleep -Seconds 2
    if ($bridgeProc.HasExited) {
        Say '桥接启动失败，日志：' 'Red'
        Get-Content $BridgeLog -ErrorAction SilentlyContinue | Select-Object -Last 10
        Get-Content "$BridgeLog.err" -ErrorAction SilentlyContinue | Select-Object -Last 10
        exit 1
    }
}

# ------------------------------------------------------------------ 2. Cookie
Step '签发 Cookie（30 天有效）'
$cookie = (& $node $mintScript "$Authority" '30').Trim()
if (-not $cookie -or $cookie -notmatch '^dsh-auth-') {
    Say '签发 Cookie 失败：请确认 DSH 已在本机正常使用过。' 'Red'
    if ($bridgeProc) { try { $bridgeProc.Kill() } catch {} }
    exit 1
}
try { Set-Clipboard -Value $cookie } catch {}

# ------------------------------------------------------------------ 3. 本机地址
$lanIp = $null
try {
    $lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -like '192.168.*' -or $_.IPAddress -like '10.*' } |
        Select-Object -First 1).IPAddress
} catch {}

# ------------------------------------------------------------------ 4. 公网隧道（尽力而为）
$tunnelProc = $null
$publicUrl = $null
$tunnelNote = $null

$ngrok = Find-Ngrok
$cf = Find-Cloudflared

# 隧道已经在跑就别再开一条：这既是「复用用户常驻隧道」，也让固定域名不会被抢
$runningTunnel = Get-NgrokTunnelUrl
if ($runningTunnel) {
    Step "检测到已在运行的 ngrok 隧道，直接复用：$runningTunnel"
    $publicUrl = $runningTunnel
}

if ($publicUrl) {
    # 已复用，什么都不用做
} elseif ($ngrok) {
    # 0) 已经在跑的 ngrok 直接复用（用户的常驻隧道），别抢固定域名
    $existing = Get-NgrokTunnelUrl
    if ($existing) {
        Step "复用已在运行的 ngrok 隧道：$existing"
        $publicUrl = $existing
    } else {
        # 1) 没配 authtoken：引导注册（官网 Share Localhost → Get started）
        if (-not (Test-NgrokAuthtoken)) {
            Warn 'ngrok 已安装但还没登录。注册取 token 只要两步：'
            Warn '   打开 https://dashboard.ngrok.com/get-started/setup  →  选 Share Localhost'
            Warn '   左侧 Getting Started → Your Authtoken，复制那串；Domains 页还能拿到免费固定域名'
            $token = Read-Host "  把 Authtoken 粘贴到这里（直接回车 = 跳过 ngrok，改用 cloudflared/局域网）"
            if ($token) {
                & $ngrok.Source config add-authtoken $token.Trim() | Out-Null
                if (Test-NgrokAuthtoken) { Step 'Authtoken 已写入 ngrok 配置' }
            }
        }

        # 2) 固定域名：免费账号在 Domains 页自带一个 xxx.ngrok-free.app
        $domainFile = Join-Path $Work 'ngrok-domain.txt'
        $domain = if (Test-Path $domainFile) { (Get-Content $domainFile -Raw).Trim() } else { $null }
        if (-not $domain) {
            Warn "固定域名的地址形如 xxxx-yyyy.ngrok-free.app（https://dashboard.ngrok.com/domains）"
            $domain = Read-Host "  想要固定域名就粘进来（直接回车 = 用随机地址，每次重开会变）"
            if ($domain) { $domain.Trim() | Set-Content -Path $domainFile -Encoding UTF8 }
        }

        if (Test-NgrokAuthtoken) {
            $args = @('http', "$BridgePort", '--host-header', $Authority)
            if ($domain) { $args += @('--url', $domain.Trim()) }
            Step ("用 ngrok 开隧道{0}…" -f $(if ($domain) { "（固定域名 $domain）" } else { '（随机地址）' }))
            if (Test-Path $TunnelLog) { Remove-Item $TunnelLog -Force -ErrorAction SilentlyContinue }
            $tunnelProc = Start-Process -FilePath $ngrok.Source -ArgumentList $args `
                -PassThru -WindowStyle Hidden -RedirectStandardOutput "$TunnelLog.out" -RedirectStandardError $TunnelLog
            Step '等待 ngrok 地址（从本地 4040 API 读取）…'
            $publicUrl = Wait-ForNgrokUrl 45
            if (-not $publicUrl) {
                $tunnelNote = 'ngrok 已启动但没拿到地址，检查 $TunnelLog；局域网地址仍然可用。'
            }
        } else {
            $tunnelNote = 'ngrok 未登录，已跳过（可以用 cloudflared 或只用局域网）。'
        }
    }
} elseif ($cf) {
    if (Test-Http 'https://api.trycloudflare.com' 6) {
        Step "用 cloudflared 开免费隧道（$([math]::Round((Get-Item $cf).Length / 1MB, 0)) MB，免注册）"
        if (Test-Path $TunnelLog) { Remove-Item $TunnelLog -Force -ErrorAction SilentlyContinue }
        $tunnelProc = Start-Process -FilePath $cf `
            -ArgumentList @('tunnel', '--url', "http://127.0.0.1:$BridgePort", '--no-autoupdate') `
            -PassThru -WindowStyle Hidden -RedirectStandardOutput "$TunnelLog.out" -RedirectStandardError $TunnelLog
    } else {
        $tunnelNote = 'Cloudflare 隧道服务在当前网络不可达（api.trycloudflare.com 超时），已跳过公网隧道。'
    }
} else {
    $newCf = $null
    if (Test-Http 'https://api.trycloudflare.com' 6) {
        $newCf = Download-Cloudflared
    } else {
        $tunnelNote = '当前网络连不上 Cloudflare 隧道服务，且本机没有 cloudflared，已跳过公网隧道。'
    }
    if ($newCf) {
        Step '用下载好的 cloudflared 开免费隧道…'
        if (Test-Path $TunnelLog) { Remove-Item $TunnelLog -Force -ErrorAction SilentlyContinue }
        $tunnelProc = Start-Process -FilePath $newCf `
            -ArgumentList @('tunnel', '--url', "http://127.0.0.1:$BridgePort", '--no-autoupdate') `
            -PassThru -WindowStyle Hidden -RedirectStandardOutput "$TunnelLog.out" -RedirectStandardError $TunnelLog
    } elseif (-not $tunnelNote) {
        $tunnelNote = 'cloudflared 下载失败（GitHub 及镜像均不可达），已跳过公网隧道。'
    }
}

if ($tunnelProc) {
    Step '等待公网地址（首次 10-30 秒）…'
    $publicUrl = Wait-ForTunnelUrl 60
    if (-not $publicUrl) {
        $tunnelNote = '隧道已启动但没拿到公网地址（可能被网络限制）。'
        if (Test-Path $TunnelLog) {
            Get-Content $TunnelLog -ErrorAction SilentlyContinue | Select-Object -Last 5 | ForEach-Object { Warn $_ }
        }
    }
}
if ($tunnelNote) { Warn $tunnelNote }

# ------------------------------------------------------------------ 5. 输出
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('')
$lines.Add('==========================  照抄进手机 App  ==========================')
$lines.Add('')
$lines.Add('【第一步】让手机和电脑连同一个 WiFi / 同一个路由器')
$lines.Add('')
if ($lanIp) {
    $lines.Add(('  DSH 地址:   http://{0}:{1}' -f $lanIp, $BridgePort))
} else {
    $lines.Add('  DSH 地址:   未检测到局域网 IP —— 请让电脑先连上 WiFi，再重跑本脚本')
}
$lines.Add('')
if ($publicUrl) {
    $lines.Add('【人在外面时（4G/5G、别家 WiFi）】')
    $lines.Add(('  DSH 地址:   {0}' -f $publicUrl))
    $lines.Add('')
} else {
    $lines.Add('【人在外面时】当前网络没有可用的公网隧道，只能在同一个 WiFi 下使用。')
    $lines.Add('           要随时随地用，需要先有一个内网穿透服务（cpolar / natapp / 花生壳），')
    $lines.Add('           或自己的云服务器 + frp；装好后把域名填到 App 的「自定义」档。')
    $lines.Add('')
}
$lines.Add('【第二步】Cookie（已复制到剪贴板，直接粘贴进 App 的「Cookie」框）')
$lines.Add('  ' + $cookie)
$lines.Add('')
$lines.Add('【第三步】App 里选「自定义」档 -> 填上面地址 -> 粘 Cookie -> 连接')
$lines.Add('           电脑端保持本窗口开着；关掉窗口 = 断开手机')
$lines.Add('======================================================================')

foreach ($l in $lines) { Say $l }
$lines -join "`r`n" | Set-Content -Path $InfoFile -Encoding UTF8
Say ''
Say "  以上内容也写到了：$InfoFile" 'DarkGray'
Say '  按 Ctrl+C 或直接关闭窗口退出。' 'DarkGray'
Say ''

# ------------------------------------------------------------------ 6. 保活
try {
    while ($true) {
        Start-Sleep -Seconds 3
        if ($bridgeProc -and $bridgeProc.HasExited) { Say '桥接已退出，请重新运行本脚本。' 'Red'; break }
        if ($tunnelProc -and $tunnelProc.HasExited) {
            Say '隧道已退出；局域网地址仍然可用。' 'Yellow'
            $tunnelProc = $null
        }
    }
} finally {
    foreach ($p in @($tunnelProc, $bridgeProc)) {
        if ($p -and -not $p.HasExited) { try { $p.Kill() } catch {} }
    }
}
