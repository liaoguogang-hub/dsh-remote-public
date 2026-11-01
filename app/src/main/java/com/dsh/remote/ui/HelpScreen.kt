package com.dsh.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.dsh.remote.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In-app help: the README, distilled to what a phone user needs while staring at the connect form.
 * Kept in code (not an asset) so it ships in the APK and works offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("帮助", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            SectionTitle("三步连上")
            Numbered(1, "电脑上双击 connect.cmd", "它会自动起桥接、开隧道、签 Cookie，然后打印「DSH 地址」和「Cookie」两行。")
            Numbered(2, "把地址和 Cookie 粘进设置页", "设置 → 网络连接方式下面就是地址框和 Cookie 框。")
            Numbered(3, "点「连接」，并让电脑那个黑窗口一直开着", "窗口关掉就断开。下次要用重新双击一次即可。")

            Spacer(Modifier.height(16.dp))

            SectionTitle("connect.ps1 怎么用")
            Paragraph("仓库里有两个文件：connect.cmd 是双击入口，connect.ps1 是真正干活的脚本。")
            Numbered(1, "最省事：双击 connect.cmd", "它会用 PowerShell 调用同目录的 connect.ps1，跑完停在窗口里等你按任意键。")
            Numbered(2, "想自己看输出：在 PowerShell 里跑", "打开脚本所在文件夹，右键「在终端中打开」，然后执行：")
            Code("powershell -NoProfile -ExecutionPolicy Bypass -File ./connect.ps1")
            Numbered(3, "它做了什么", "起桥接（Host 改写 127.0.0.1:1815）→ 签 30 天 Cookie → 有 ngrok 用 ngrok，没有就试 cloudflared → 打印地址和 Cookie。")
            Numbered(4, "怎么停", "回到那个黑窗口按 Ctrl+C，或直接关窗口；桥接和隧道会一起退出。")
            Note("执行策略报错时用 -ExecutionPolicy Bypass（上面命令已带），不需要管理员权限，也不写注册表。")
            Note("脚本只在 %LOCALAPPDATA%/dsh-remote/ 下写日志和记固定域名；Cookie 会复制到剪贴板，方便直接粘贴。")

            Spacer(Modifier.height(16.dp))

            SectionTitle("扫码配对（推荐）")
            Paragraph("不想手抄地址和 Cookie，就用扫码：手机对准电脑上的二维码，自动填好并连上。")
            Numbered(1, "电脑上生成二维码", "装好 DSH 插件后运行：dsh-remote qr（或浏览器打开 http://127.0.0.1:6300/_dsh-remote/pair）。")
            Numbered(2, "手机点右上角的扫码按钮", "在本页右上角有扫码图标；连上以后还能从「设置」右上角再次扫码。")
            Numbered(3, "对准屏幕扫描", "扫完自动填地址、自动换凭据、自动连接，不需要再点连接。")
            Note("二维码里不含 Cookie，只含一个 10 分钟内有效、用一次就作废的配对码；兑换成功即失效，截图泄漏也没用。")
            Note("扫码失败怎么办：让手机和电脑连同一个 WiFi 再扫；仍不行就在电脑上运行 dsh-remote connect，把它打印的地址和 Cookie 手动填到下面的输入框。")

            Spacer(Modifier.height(14.dp))

            SectionTitle("地址怎么选")
            KeyValue("局域网", "手机和电脑同一个 WiFi。形如 http://192.168.1.23:6300，最快、最稳。")
            KeyValue("ngrok", "人在外面时用。形如 https://xxxx.ngrok-free.dev，需要注册（见下）。")
            KeyValue("自定义", "任何符合 DSH 的地址，例如你自己反代出来的域名。")

            Spacer(Modifier.height(16.dp))

            SectionTitle("Cookie 和 Token 从哪来")
            Paragraph("Cookie 是 30 天有效的登录凭据，connect.cmd 会替你签好并复制到剪贴板，直接粘贴即可。")
            Paragraph("Token 是临时凭据：DSH 每次重启都会变。日常用 Cookie 更省事。")
            Note("桥接必须把 Host 改写成 127.0.0.1:1815，否则 DSH 会拒绝（403），cookie 的 authority 也对不上。connect.cmd 已内置。")

            Spacer(Modifier.height(16.dp))

            SectionTitle("想要永久固定域名：用 ngrok（需注册）")
            Paragraph("cloudflared 的免费隧道每次重启地址都会变；要固定地址就注册一个免费 ngrok 账号。")
            Image(
                painter = painterResource(R.drawable.ngrok_dashboard),
                contentDescription = "ngrok 后台：左栏三处要点",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .padding(bottom = 10.dp),
            )
            Paragraph("上图红框就是全部要点：① Share Localhost ② Your Authtoken ③ Domains。")
            Numbered(1, "打开 dashboard.ngrok.com/get-started/setup", "首页选 Share Localhost ——「把本机应用挂到公网固定地址」就是这个需求。")
            Numbered(2, "取 Authtoken", "左侧 Getting Started → Your Authtoken，复制那串。")
            Numbered(3, "取免费固定域名", "左侧 Universal Gateway → Domains，形如 xxxx-yyyy.ngrok-free.app。")
            Numbered(4, "在电脑上登录一次", "把 token 交给 connect.cmd（它会问一次），或手动执行下面这条：")
            Code("ngrok config add-authtoken <你的 token>")
            Paragraph("之后双击 connect.cmd 就会自动用固定域名，并把地址记在本地。")
            Note("不要给 ngrok 开 Basic Auth：App 用的是 DSH 签名 cookie，Basic Auth 会让每个请求先被 401 拦掉。")
            Note("免费额度约 40 连接/分钟、1 GB/月。偶发断连先怀疑额度，长连接 + 流式输出日常够用。")

            Spacer(Modifier.height(16.dp))

            SectionTitle("常见问题")
            Faq("一直显示「同步中」", "网络断了会自动重连；先确认电脑端窗口还开着，再确认手机能打开那个地址（浏览器访问会返回 401，这是正常的）。")
            Faq("地址突然连不上", "公网隧道每次重开地址可能变；用 connect.cmd 重新看一次，或改用 ngrok 固定域名。")
            Faq("Cookie 过期了", "30 天到期。重新跑一次 connect.cmd，把新 Cookie 粘进来即可，不用重装 App。")
            Faq("提示 host-unauthorized", "电脑端桥接没起，或者 Host 没被改写。确认 connect.cmd 的窗口还在，别手动改地址。")
            Faq("想换台电脑连", "在设置里改地址 + 粘那台电脑的 Cookie 即可，App 不用重装。")

            Spacer(Modifier.height(22.dp))

            Text(
                "以上内容来自仓库 README.md。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Numbered(number: Int, title: String, body: String) {
    Row(Modifier.padding(bottom = 10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.size(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Text(
            text = key,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Code(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Note(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Faq(question: String, answer: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            text = "Q：$question",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "A：$answer",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
