package com.dsh.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.remote.data.ConnectMode

/**
 * First-run configuration. The form itself lives in [NetworkForm] so the in-chat settings sheet
 * shows exactly the same thing; the explanation moved to [HelpScreen].
 */
@Composable
fun SetupScreen(
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
    onDismissError: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = "连接你的电脑",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "在电脑上双击 connect.cmd，把它打印的地址和 Cookie 填到下面。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            NetworkForm(
                mode = mode,
                urlDraft = urlDraft,
                tokenDraft = tokenDraft,
                cookieDraft = cookieDraft,
                customHistory = customHistory,
                connecting = connecting,
                error = error,
                onModeChange = onModeChange,
                onUrlChange = onUrlChange,
                onTokenChange = onTokenChange,
                onCookieChange = onCookieChange,
                onPickHistory = onPickHistory,
                onDeleteHistory = onDeleteHistory,
                onConnect = onConnect,
                onDismissError = onDismissError,
                onOpenHelp = onOpenHelp,
            )

            Spacer(Modifier.height(22.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = "第一次用？",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "点下面的「打开帮助」，里面写了完整的连接步骤、ngrok 固定域名怎么弄、以及连不上时先查哪里。",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onOpenHelp) { Text("打开帮助", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
