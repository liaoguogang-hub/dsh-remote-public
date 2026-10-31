# DSH Remote — Android 远程控制器

手机原生 App，直接控制电脑上的 **DeepSeek Harness (DSH)**。不是 WebView 套壳：
Kotlin/Compose 直连 DSH 自带的 HTTP API + WebSocket 网关。

这是**公共版**，与作者自用版的唯一差别：

| | 自用版 | 本仓库（公共版） |
|---|---|---|
| 连接方式 | 局域网 / 自建中继 / 自定义 | 局域网 / **ngrok 隧道** / 自定义 |
| 内置服务器地址 | 有 | **无** |
| 内置登录凭证 | 有（30 天 cookie） | **无** |
| 包名 | `com.dsh.remote` | `com.dsh.remote.ngrok`（可与自用版共存） |
| 应用名 / APK | `DSH` / `DSH-Remote-v*.apk` | `DSH Remote` / `DSH Remote-v*.apk` |

功能与 UI 与自用版 4.12.2 完全一致。

## 功能

| 能力 | 状态 |
|---|---|
| 三种连接方式（局域网 / ngrok / 自定义），地址与凭证首次填写后本地保存 | ✅ |
| 会话列表：搜索、排序（最近更新 / 手动）、归档筛选（隐藏 / 全部 / 仅归档） | ✅ |
| 会话列表默认显示最近 10 条，「展开其余 N 个会话」每次 +5 | ✅ |
| 长按会话：重命名 / 归档会话（或取消归档） | ✅ |
| 发消息、逐字流式回复、随时中断 | ✅ |
| 历史分页（「加载更早的消息」）与本地缓存（先显示上次内容再同步） | ✅ |
| 模型选择（分组目录 + 切换） | ✅ |
| 权限模式 | ⚠️ 只能读取当前值（DSH 未开放远程切换权限的接口） |
| 自定义地址历史（点选填入 / 删除） | ✅ |

## 三步连上（推荐：一键脚本）

1. **电脑上双击 `connect.cmd`**（和 App 一起发布的文件）。
   它会自动做四件事：起桥接 → 签发 30 天 Cookie → 尽量开公网隧道 → 打印结果。
2. 等黑窗口打印出这两行：
   - `DSH 地址: http://192.168.x.x:6300`（同一 WiFi，最快）
   - `Cookie: dsh-auth-…`（已自动复制到剪贴板）
   如果网络允许，还会多给一行公网地址 `https://xxx.trycloudflare.com`（人在外面时用）。
3. **App 里选「自定义」档** → 把地址粘进「DSH 地址」框 → Cookie 粘进「Cookie」框 → 点连接。

> 电脑端那个黑窗口要保持开着；关掉 = 断开手机。下次用重新双击一次，抄新的地址和 Cookie 即可。
> 结果同时会写到同目录的 `connect-info.txt`。

### 公网隧道说明

脚本按这个顺序尝试，**局域网地址永远可用**，公网则尽力而为：

| 情况 | 结果 |
|---|---|
| 装了 `ngrok` 且在 PATH 里 | 用 ngrok 开隧道 |
| 有 `cloudflared`（脚本会自动找/下载）且 `api.trycloudflare.com` 可达 | 免费 trycloudflare 地址，免注册 |
| 两者都不行（常见于受限网络） | 只给局域网地址，并提示需要自备内网穿透（cpolar / natapp / 花生壳 / 自建 frp） |

### 用 ngrok 拿一个永久固定域名（可选，需注册）

cloudflared 的免费 quick tunnel **每次重启地址都会变**；想要固定域名就用 ngrok 免费账号。


![ngrok 注册要点：左栏三处](docs/ngrok-dashboard-annotated.png)

上图红框就是全部要点：① Share Localhost ② Your Authtoken ③ Domains。

**注册（约 2 分钟）**

1. 打开 <https://dashboard.ngrok.com/get-started/setup>，在首页选 **Share Localhost**
   （「Put your local app on a public URL」——它明确写着 *stable, auth-protected URL*）；
2. 跟着 **Get started** 走完，进 dashboard 后取两样东西：
   - **Authtoken**：左侧 `Getting Started` → `Your Authtoken`
     <https://dashboard.ngrok.com/get-started/your-authtoken>
   - **免费固定域名**：左侧 `Universal Gateway` → `Domains`，免费账号自带一个
     `xxxx-yyyy.ngrok-free.app` <https://dashboard.ngrok.com/domains>
3. 下载 ngrok 单文件（同样免安装、不需要管理员权限），放进 `tools\ngrok.exe`，或装到 PATH。

**配置（只需一次）**

```powershell
tools\ngrok.exe config add-authtoken <你的 Authtoken>
```

然后双击 `connect.cmd`：它会自己发现 ngrok、问你一次固定域名（记在
`%LOCALAPPDATA%\dsh-remote\ngrok-domain.txt`），之后每次都复用同一个地址。

> - **Host 必须改写**：脚本用 `--host-header=127.0.0.1:1815`，否则 DSH 的信任栅栏会 403，
>   而且 cookie 的 authority 对不上。
> - **不要加 ngrok 的 Basic Auth**：App 是用 DSH 的签名 cookie 认证的，Basic Auth 会让每个
>   API 调用先被 ngrok 拦下（401），App 连不上。安全性由 30 天签名的 cookie 保证。
> - **免费额度**：约 40 连接/分钟、1 GB/月。App 是长连接 + 流式输出，日常够用；
>   偶发断连多半是额度或连接数超限，可换付费或 cloudflared。

### 自己下载 cloudflared（可选，单文件免安装、不需要管理员权限）

脚本会优先在 `connect.cmd` 同目录、`tools\`、`%LOCALAPPDATA%\dsh-remote\` 等位置找
`cloudflared.exe`；找不到才下载。想自己放一份，按下面来：

```powershell
# 1) 镜像优先：国内直连 GitHub 下 52 MB 基本必超时
$url = "https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
# 官方直连（网络允许时）：
# $url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"

$dest = "tools\cloudflared.exe"
New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
Invoke-WebRequest -Uri $url -OutFile $dest -TimeoutSec 300 -UseBasicParsing

# 2) 一定要验签：镜像可能给错文件，但签名伪造不了
Get-AuthenticodeSignature $dest | Format-List Status, SignerCertificate
```

合格标准：`Status = Valid`，`Subject` 里含 `Cloudflare, Inc.`。
参考值（2026.10.5 版）：`55,343,328` 字节，
SHA256 `DB4E4C413612A0916F3E1679D3CAAF376F94911952D163028FB726FB2E0516A9`。

> 脚本自己下载时也会做同样的验签（`Get-AuthenticodeSignature` + 主体含 Cloudflare），
> 校验不过就丢弃并换下一个源 —— **不要用哈希对比代替验签**，镜像可以把哈希一起改掉。

### 手工方式（想自己控制时）

```bash
node lan-proxy.mjs 6300 127.0.0.1:1815     # 桥接：把 Host 改写成 DSH 认可的 127.0.0.1:1815
ngrok http 6300 --host-header=127.0.0.1:1815   # 或 cloudflared tunnel --url http://127.0.0.1:6300
node mint.mjs "127.0.0.1:1815" 30              # 签发 30 天 Cookie
```

```text
浏览器打开 DSH 页面 → F12 → Application → Cookies → 复制会话 cookie
  （cookie 只对签发时的 authority 有效，所以桥接必须改写 Host 到同一个值）
```

## 安全说明

- **本仓库不包含任何地址、Token 或 Cookie**：`DshDefaults.kt` 里全是空字符串，首次连接在 App 里填。
- **Cookie 由你自己的电脑签发**：脚本用 `~/.dsh/.credentials.yaml` 里的密钥签一个 30 天有效的凭据，
  只在你自己机器和手机之间流动，不要提交到任何仓库。
- **局域网默认是明文 HTTP**：`lan-proxy.mjs` 监听 `0.0.0.0:6300`，同一 WiFi 内 Anyone 能访问它，
  所以请只在可信网络里用局域网模式；要跨网请用 ngrok / cloudflared 这类带 TLS 的隧道。
- **App 允许明文与用户证书**（`network_security_config.xml`）：DSH 通常跑在明文 HTTP 上，
  自签 CA 的中转也要能信任；代价是它不会阻止你连明文地址，所以地址来源要自己把关。
- **别把 ngrok 的 Basic Auth 和 DSH 的 Cookie 混用**：App 只带 DSH 的 Cookie，
  Basic Auth 会让每个请求先被隧道 401 拦掉。

## 构建

```bash
./gradlew assembleRelease      # 产物：dist/DSH Remote-v<version>.apk
```

需要 JDK 17 与 Android SDK（`local.properties` 指向本机 SDK）。

## 版本

当前 `4.12.2`（versionCode 58），与自用版 4.12.2 功能一致。
