# 发布手册（Releasing）

这个仓库同时承载**两个可发布物**，版本号与 tag 前缀刻意分开，看 tag 就知道发的是哪个：

| 产物 | 版本号 | tag | 分发方式 |
|---|---|---|---|
| 电脑端插件 `dsh-remote-hub` | `0.x.y` | `v0.x.y` | npm |
| Android App（公共版） | `4.x.y` | `v4.x.y` | GitHub Release（附 APK） |

自用版 App 在私有仓库维护，不发 Release，但版本号也走 `4.x.y`（与公共版独立递增，versionCode 不复用）。

---

## 1. 发布插件

### 改三处版本号（漏一处就会出现「npm 上是新的、代码里报旧的」）

```bash
# 1) package.json 的 version
# 2) lib/index.js 里的 export const version = '0.x.y'
# 3) （可选）cordis.patch.yml / README 里提到版本的地方
grep -rn "0\.4\.1" package.json lib/index.js | head
```

### 校验 + 发布

```bash
node --check lib/*.js bin/dsh-remote.mjs
npm pack --dry-run                     # 确认文件清单（应只有 lib/ bin/ cordis.patch.yml package.json README LICENSE）
npm publish                            # publishConfig.registry 已指向 registry.npmjs.org
```

`npm publish` 需要**带 bypass 2FA 的 granular token**，否则报 403。发布后 npm 可能短暂显示**上一个版本**（staged 审核），等 1–2 分钟再用 `npm view` 确认：

```bash
npm view dsh-remote-hub version --registry=https://registry.npmjs.org
curl -s https://registry.npmjs.org/dsh-remote-hub | python -c "import json,sys;d=json.load(sys.stdin);print(d['dist-tags'])"
```

### 打 tag

```bash
git tag -a v0.x.y -m "dsh-remote-hub v0.x.y — <一句话>" && git push origin v0.x.y
```

插件 tag **不建 Release**（插件通过 npm 分发）。

---

## 2. 发布 App

### 改版本号

`app/build.gradle.kts` 顶部两行**必须同时改**，且 versionCode 只增不减：

```kotlin
val appVersion = "4.21.4"
val appVersionCode = 81        // 上一版 +1
```

### 构建与真机验证

```bash
./gradlew.bat assembleRelease        # 产物落在 dist/DSH-Remote-v<version>.apk
adb install -r dist/DSH-Remote-v4.21.4.apk
adb shell dumpsys package com.dsh.remote.ngrok | grep versionName
```

至少确认：首次页右上角有扫码+帮助、帮助页文案正确、扫码能打开相机且是竖屏。

### 建 Release（描述里必须带下载链接）

```bash
git tag -a v4.21.4 -m "DSH Remote v4.21.4 — <一句话>"
git push origin v4.21.4
gh release create v4.21.4 "dist/DSH-Remote-v4.21.4.apk" \
  --title "DSH Remote v4.21.4 — <一句话>" --notes-file .relnotes.md
```

`.relnotes.md` 开头放下载区（市场站可能抓 Release 描述作为兜底展示，README 抓取目前不刷新）：

```markdown
## ⬇️ 下载手机端 App

**[DSH-Remote-v4.21.4.apk](https://github.com/liaoguogang-hub/dsh-remote-public/releases/download/v4.21.4/DSH-Remote-v4.21.4.apk)** · 2.8 MB · Android 8.0+
```

同步更新 `README.md` 的下载区（版本号 + 下载 URL 两处）。

### 发布后核对

```bash
gh release list --limit 3
gh release view v4.21.4 --json tagName,assets
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" -L \
  https://github.com/liaoguogang-hub/dsh-remote-public/releases/download/v4.21.4/DSH-Remote-v4.21.4.apk
```

---

## 2.5 装进桌面端 profile（`--profile desktop` 被应用独占）

`dsh plugin --profile desktop add ...` 会被拒绝：

```
error: profile "desktop" is managed exclusively by the Electron application
```

桌面端 profile（`~/.dsh/profiles/desktop`）由 Electron 应用托管，只能手工两步：

```bash
cd ~/.dsh/profiles/desktop

# 1) 用应用自带的 pnpm 装包；必须显式指定官方 registry，
#    否则 profile 里的镜像源可能给出旧版本（实测装到过 0.1.0）
NODE="…/primary-runtime/dependencies/node/bin/node.exe"
PNPM="…/primary-runtime/dependencies/pnpm/bin/pnpm.mjs"
"$NODE" "$PNPM" add dsh-remote-hub@<version> --registry=https://registry.npmjs.org

# 2) 在 cordis.patch.yml 里插入一行（改前先备份）
- insert:
    - id: dsh-remote
      name: dsh-remote-hub
      config:
        enabled: true
        port: 6300
        authority: '127.0.0.1:1815'
        tunnel: auto
        publicUrl: 'https://你的中转'   # 不需要自建中转就删掉这行
        upstreamPort: 0
```

**桌面端会热加载这行配置**：保存 `cordis.patch.yml` 后插件立刻被加载、桥接在宿主进程里起来，
`netstat -ano | findstr :6300` 会指向 `DeepSeek Harness.exe` 的 PID（而不是 node），
这正是"桥接随 DSH 常驻、不再被 shell 回收"的标志。不改配置的话，最稳的验证方式是完全重启应用一次。

## 3. 公共版 / 自用版的同步

两棵树共用一套 UI 代码，差异只在**凭据与映射**。从公共版同步到自用版时，逐个确认这几个文件：

```
app/src/main/java/com/dsh/remote/
├── ui/ScanIcon.kt          # 直接复制
├── ui/ChatScreen.kt        # 直接复制
├── ui/ChatViewModel.kt     # 复制后检查扫码映射（见下）
├── net/DshClient.kt        # 直接复制
├── MainActivity.kt         # 直接复制
├── ui/NetworkForm.kt       # 复制后把 Tunnel 标签改回「Cloudflare」
├── ui/SetupScreen.kt       # 文案按自用版改写
├── ui/HelpScreen.kt        # 各自独立编写，不要复制
└── data/DshDefaults.kt     # 绝不复制：自用版才有内置地址与 Cookie
```

**扫码映射不同，改代码时别改反**：

| 二维码字段 | 公共版 | 自用版 |
|---|---|---|
| `l` 局域网（数组） | 局域网 | 局域网 |
| `u` 自动隧道 | ngrok 档 | 自定义 |
| `r` 自建中转 | **忽略** | Cloudflare |

`ChatViewModel.pairFromQr` 里对应两段注释写着 `Self-use mapping` / 公共版忽略 `r`，同步时保留注释。

---

## 4. 踩过的坑（看一遍省一小时）

| 坑 | 表现 | 做法 |
|---|---|---|
| 热重载不回收监听 | 换版本后端口被旧桥接占着，配对页乱码/打不开 | **升级插件后完全退出 DSH 再启动** |
| 端口占用 | `EADDRINUSE`、`bridgePort: 0` | 0.3.1+ 会自动采纳/换端口；看 `dsh-remote doctor` |
| 固定 authority 假设 | 别的机器 401 | 保持 `authority: 127.0.0.1:1815` 固定（DSH 校验的是 Cookie.authority == Host），**不要**改成跟随端口，否则重启即失效 |
| 环境变量串台 | 公共版也显示自建中转 | 公共版 `connect.ps1` 清空 `DSH_REMOTE_PUBLIC_URL`；两个 `connect.ps1` 都传 `--restart` |
| `.cmd` 编码 | 窗口一闪而过 | `connect.cmd` 纯 ASCII + CRLF；`connect.ps1` 必须 UTF-8 **带 BOM** |
| 市场站缓存 | 详情页看不到 README 的下载区 | 放弃依赖它，改成 Release 描述里带下载链接 |
| 临时文件误提交 | 仓库多出 `.gitmsg.txt` | 已加入 `.gitignore` |
| tag 混用 | 分不清 App 还是插件 | `v4.*` = App，`v0.*` = 插件 |

---

## 5. 一键自检

发版前后都可以跑：

```bash
dsh-remote doctor                      # 凭据/端口/公网地址类型
dsh-remote status                      # 当前真实端口、局域网、公网、Cookie
curl -s http://127.0.0.1:<port>/_dsh-remote/health
```
