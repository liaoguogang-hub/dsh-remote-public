package com.dsh.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.remote.data.ConnectMode

private data class ModeInfo(val mode: ConnectMode, val label: String, val hint: String)

private val MODES = listOf(
    ModeInfo(ConnectMode.Lan, "局域网", "同一 WiFi"),
    ModeInfo(ConnectMode.Tunnel, "ngrok", "公网隧道"),
    ModeInfo(ConnectMode.Custom, "自定义", "手填地址"),
)

/**
 * The whole "what address, which credential" form.
 *
 * Shared by the first-run screen and the in-chat settings sheet: one implementation means the
 * two surfaces can never drift apart. The help text lives in [HelpScreen], not here.
 */
@Composable
fun NetworkForm(
    mode: ConnectMode,
    urlDraft: String,
    tokenDraft: String,
    cookieDraft: String,
    customHistory: List<String>,
    connecting: Boolean,
    error: String?,
    onModeChange: (ConnectMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onCookieChange: (String) -> Unit,
    onPickHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onConnect: () -> Unit,
    onCancelConnect: () -> Unit,
    onDismissError: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    Text(
        text = "网络连接方式",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MODES.forEach { info ->
            ModeChip(
                info = info,
                selected = info.mode == mode,
                enabled = !connecting,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(info.mode) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = urlDraft,
        onValueChange = onUrlChange,
        label = { Text("DSH 地址") },
        placeholder = { Text("http://192.168.0.129:6204") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
        enabled = !connecting,
    )

    if (mode == ConnectMode.Custom && customHistory.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "历史地址（点一下填入，右侧可删除）",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        customHistory.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = entry,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !connecting) { onPickHistory(entry) },
                )
                TextButton(onClick = { onDeleteHistory(entry) }, enabled = !connecting) {
                    Text("删除", fontSize = 10.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = tokenDraft,
        onValueChange = onTokenChange,
        label = { Text("启动 Token") },
        placeholder = { Text("token=") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        enabled = !connecting,
    )

    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = cookieDraft,
        onValueChange = onCookieChange,
        label = { Text("Cookie（到期续期用，可留空）") },
        placeholder = { Text("dsh-auth-…=v1.…") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
        enabled = !connecting,
    )

    Spacer(Modifier.height(8.dp))

    Spacer(Modifier.height(6.dp))
    // While a connection is in flight the primary action becomes "stop": without it the only way
    // out of a stuck attempt was to wait for the timeout or kill the app.
    if (connecting) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("正在连接…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onCancelConnect,
                modifier = Modifier.height(40.dp),
            ) {
                Text("停止连接", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    Button(
        onClick = onConnect,
        enabled = !connecting && urlDraft.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Text("连接", fontSize = 15.sp)
    }

    if (error != null) {
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onDismissError) { Text("知道了", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun ModeChip(
    info: ModeInfo,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(56.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(info.label, fontSize = 12.sp, color = fg)
            Text(info.hint, fontSize = 9.sp, color = fg.copy(alpha = 0.75f))
        }
    }
}
