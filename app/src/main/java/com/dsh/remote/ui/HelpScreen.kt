package com.dsh.remote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsh.remote.R

/**
 * In-app help. Kept short on purpose: the three things a stuck user needs are (1) how to pair,
 * (2) which address to pick, (3) why a free quick tunnel is not good enough and ngrok is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    var zoomImage by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
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
            SectionTitle("扫码配对")
            Numbered(1, "电脑上生成二维码", "双击 connect.cmd，或运行 dsh-remote qr。会打印地址和 Cookie，并自动打开配对页。")
            Numbered(2, "手机点右上角扫码", "本页右上角有扫码按钮；连上以后也能从「设置」右上角再扫。")
            Numbered(3, "对准屏幕", "扫完自动选局域网或公网、填地址、换凭据并直接连接。")

            Spacer(Modifier.height(18.dp))

            SectionTitle("地址怎么选")
            KeyValue("局域网", "手机和电脑同一 WiFi，最快最稳。")
            KeyValue("ngrok", "人在外面用，固定域名。")
            KeyValue("自定义", "自己的反向代理地址。")
            Paragraph("扫码一次会同时存下局域网和 ngrok 两档，之后自动优先走局域网，不用再扫。")

            Spacer(Modifier.height(18.dp))

            // The single most expensive misunderstanding: a free quick tunnel changes its URL on
            // every restart, which silently invalidates whatever the phone saved. That is exactly
            // why registering ngrok (a stable domain) is worth the two minutes.
            WarningCard(
                title = "免费隧道每次重启地址都会变",
                body = "cloudflared 的免费隧道重启后域名会换，手机里存的地址立刻失效，" +
                    "每次都要重新扫码。这是注册 ngrok 的唯一原因：它带一个固定域名，重启不变，扫一次长期有效。",
            )

            Spacer(Modifier.height(14.dp))

            SectionTitle("注册 ngrok（2 分钟）")
            Image(
                painter = painterResource(R.drawable.ngrok_dashboard),
                contentDescription = "ngrok 后台：左栏三处要点",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { zoomImage = true },
            )
            Spacer(Modifier.height(6.dp))
            // An explicit button, not just "tap the image": inside a scrolling column a tap on the
            // picture can be swallowed by the scroll gesture, and users do not always discover it.
            TextButton(
                onClick = { zoomImage = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看大图", fontSize = 12.sp)
            }
            Text(
                text = "红框就是全部要点：① Share Localhost ② Your Authtoken ③ Domains",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Numbered(1, "选 Share Localhost", "打开 dashboard.ngrok.com/get-started/setup，首页选 Share Localhost。")
            Numbered(2, "复制 Authtoken", "左侧 Getting Started → Your Authtoken。")
            Numbered(3, "复制固定域名", "左侧 Universal Gateway → Domains，形如 xxxx.ngrok-free.app。")
            Paragraph("然后双击 connect.cmd：它会问一次域名并记住，之后地址固定不变。")

            Spacer(Modifier.height(18.dp))

            SectionTitle("常见问题")
            Faq("一直显示「同步中」", "确认电脑端进程还在、手机和电脑在同一 WiFi，然后下拉重试。")
            Faq("提示 401 / 凭据失效", "点右上角扫码重新配对，10 秒即可。")
            Faq("二维码扫不出来", "同一 WiFi 下再试；或把 connect.cmd 打印的地址和 Cookie 手动填进设置页。")

            Spacer(Modifier.height(24.dp))
        }
    }

    // Same-window overlay instead of a Dialog: a Dialog is a separate window, which makes the
    // overlay invisible to accessibility tooling and adds no value here.
    if (zoomImage) {
        ZoomOverlay(onDismiss = { zoomImage = false })
    }
    }
}

/** Full-screen, pinch-zoomable overlay for the ngrok screenshot. */
@Composable
private fun ZoomOverlay(onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDismiss() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ngrok_dashboard),
            contentDescription = "ngrok 后台要点（放大）",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭大图", tint = Color.White)
        }
        Text(
            text = "双指缩放 · 双击放大 · 点空白处关闭",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
        )
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

/** Attention-drawing block for facts that cost the user time when missed. */
@Composable
private fun WarningCard(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
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
    Row(Modifier.padding(bottom = 6.dp)) {
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
