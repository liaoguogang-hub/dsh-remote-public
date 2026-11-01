package com.dsh.remote.ui

import com.dsh.remote.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onOpenHelp: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSelectModel: (ModelOption) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetFont: (FontChoice) -> Unit,
    onSetScale: (Float) -> Unit,
    onZoom: (Float) -> Unit,
    onChangeMode: (com.dsh.remote.data.ConnectMode) -> Unit,
    onOpenSetup: () -> Unit,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onCookieChange: (String) -> Unit,
    onPickHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onConnect: () -> Unit,
    onScanQr: () -> Unit,
    onDisconnect: () -> Unit,
    onExit: () -> Unit,
    onApproval: (ApprovalRequest, Boolean) -> Unit,
    onDismissError: () -> Unit,
    onLoadEarlier: () -> Unit,
    onSessionQueryChange: (String) -> Unit,
    onOrderByChange: (String) -> Unit,
    onArchivedFilterChange: (String) -> Unit,
    onSessionArchiveToggle: (String, Boolean) -> Unit,
    onSessionRename: (String, String) -> Unit,
) {
    val bodySize = (15f * state.fontScale).sp
    var draft by remember(state.sessionId) { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTurns by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }

    // Back on the conversation asks before leaving the app.
    BackHandler { showExit = true }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("退出 DSH？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("退出后电脑上的 agent 会继续在后台运行。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    onExit()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text("取消") }
            },
        )
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message, but not when older history was just prepended:
    // that would yank the user away from what they were reading.
    var lastFirstKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.messages.size, state.streaming, state.messages.firstOrNull()?.key) {
        val firstKey = state.messages.firstOrNull()?.key
        val prepended = lastFirstKey != null && firstKey != lastFirstKey
        lastFirstKey = firstKey
        if (prepended) return@LaunchedEffect
        val target = (state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0) - 1
        if (target >= 0) runCatching { listState.animateScrollToItem(target) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp),
            ) {
                SessionDrawer(
                    state = state,
                    onNewSession = {
                        scope.launch { drawerState.close() }
                        onNewSession()
                    },
                    onSelect = { id ->
                        scope.launch { drawerState.close() }
                        onSelectSession(id)
                    },
                    onDisconnect = onDisconnect,
                    onSessionQueryChange = onSessionQueryChange,
                    onOrderByChange = onOrderByChange,
                    onArchivedFilterChange = onArchivedFilterChange,
                    onSessionArchiveToggle = onSessionArchiveToggle,
                    onSessionRename = onSessionRename,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话列表")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = state.sessionTitle.ifBlank { "新对话" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(
                                            when {
                                                !state.connected -> MaterialTheme.colorScheme.error
                                                state.running -> Color(0xFF66BB6A)
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                            RoundedCornerShape(50),
                                        )
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = when {
                                        state.connecting -> "同步中…"
                                        !state.connected -> "已断开"
                                        state.running -> "正在执行…"
                                        else -> "已连接"
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showTurns = true },
                            enabled = state.turns.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.FormatListNumbered, contentDescription = "回合导航")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                        IconButton(onClick = onOpenHelp) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "帮助")
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
                    .imePadding(),
            ) {
                if (state.error != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.error,
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = onDismissError) { Text("关闭", fontSize = 11.sp) }
                        }
                    }
                }

                // Pinch anywhere in the transcript to zoom the conversation text.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) onZoom(zoom)
                            }
                        },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp,
                            vertical = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.hasMore || state.loadingEarlier) {
                            item(key = "__load_earlier__") {
                                TextButton(
                                    onClick = onLoadEarlier,
                                    enabled = !state.loadingEarlier,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (state.loadingEarlier) "加载中…" else "加载更早的消息",
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        items(state.messages, key = { it.key }) { message ->
                            MessageBubble(message, bodySize, state.fontChoice)
                        }
                        if (state.streaming.isNotEmpty()) {
                            item(key = "__streaming__") {
                                MessageBubble(
                                    ChatMessage("__streaming__", Role.ASSISTANT, state.streaming),
                                    bodySize,
                                    state.fontChoice,
                                )
                            }
                        }
                        if (state.messages.isEmpty() && state.streaming.isEmpty()) {
                            item(key = "__empty__") { EmptyState(state.connected) }
                        }
                    }
                }

                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    running = state.running,
                    connected = state.connected,
                    modelLabel = state.modelLabel,
                    permission = state.permissionCurrent,
                    onModelClick = { showModels = true },
                    onPermissionClick = { showPermissions = true },
                    onSend = {
                        val text = draft
                        draft = ""
                        onSend(text)
                    },
                    onStop = onStop,
                    approvals = state.pendingApprovals,
                    onApproval = onApproval,
                )
            }
        }
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }) {
            ModelSheet(state = state, onPick = { option ->
                showModels = false
                onSelectModel(option)
            })
        }
    }

    if (showPermissions) {
        ModalBottomSheet(onDismissRequest = { showPermissions = false }) {
            PermissionSheet(state = state)
        }
    }

    if (showTurns) {
        ModalBottomSheet(onDismissRequest = { showTurns = false }) {
            TurnSheet(
                turns = state.turns,
                messages = state.messages,
                onPick = { target ->
                    showTurns = false
                    scope.launch { listState.animateScrollToItem(target) }
                },
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsSheet(
                state = state,
                onSetTheme = onSetTheme,
                onSetFont = onSetFont,
                onSetScale = onSetScale,
                onChangeMode = onChangeMode,
                onUrlChange = onUrlChange,
                onTokenChange = onTokenChange,
                onCookieChange = onCookieChange,
                onPickHistory = onPickHistory,
                onDeleteHistory = onDeleteHistory,
                onConnect = onConnect,
                onDismissError = onDismissError,
                onScanQr = {
                    showSettings = false
                    onScanQr()
                },
                onOpenHelp = {
                    showSettings = false
                    onOpenHelp()
                },
                onDisconnect = {
                    showSettings = false
                    onDisconnect()
                },
            )
        }
    }
}

// -------------------------------------------------------------------- settings

@Composable
private fun SettingsSheet(
    state: ChatUiState,
    onSetTheme: (ThemeMode) -> Unit,
    onSetFont: (FontChoice) -> Unit,
    onSetScale: (Float) -> Unit,
    onChangeMode: (com.dsh.remote.data.ConnectMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onCookieChange: (String) -> Unit,
    onPickHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onConnect: () -> Unit,
    onDismissError: () -> Unit,
    onOpenHelp: () -> Unit,
    onScanQr: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onScanQr) {
                ScanIcon()
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                SettingsDropdown(
                    caption = "背景色",
                    value = state.theme.label,
                    options = ThemeMode.entries.map { it.label },
                    onPick = { picked -> ThemeMode.entries.firstOrNull { it.label == picked }?.let(onSetTheme) },
                )
            }
            Box(Modifier.weight(1f)) {
                SettingsDropdown(
                    caption = "字体",
                    value = state.fontChoice.label,
                    options = FontChoice.entries.map { it.label },
                    onPick = { picked -> FontChoice.entries.firstOrNull { it.label == picked }?.let(onSetFont) },
                )
            }
            Box(Modifier.weight(1f)) {
                SettingsDropdown(
                    caption = "字号",
                    value = (FONT_SCALES.firstOrNull { kotlin.math.abs(state.fontScale - it.first) < 0.02f }?.second)
                        ?: "${(state.fontScale * 100).toInt()}%",
                    options = FONT_SCALES.map { it.second },
                    onPick = { picked ->
                        FONT_SCALES.firstOrNull { it.second == picked }?.let { onSetScale(it.first) }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        NetworkForm(
            mode = state.mode,
            urlDraft = state.urlDraft,
            tokenDraft = state.tokenDraft,
            cookieDraft = state.cookieDraft,
            customHistory = state.customHistory,
            connecting = state.connecting,
            error = state.error,
            onModeChange = onChangeMode,
            onUrlChange = onUrlChange,
            onTokenChange = onTokenChange,
            onCookieChange = onCookieChange,
            onPickHistory = onPickHistory,
            onDeleteHistory = onDeleteHistory,
            onConnect = onConnect,
            onDismissError = onDismissError,
            onOpenHelp = onOpenHelp,
        )

        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("当前连接", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(
                    state.urlDraft,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.workspaces.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    val grouped = state.workspaces.flatMap { it.sessionIds }.toSet()
                    val inWorkspace = state.sessions.count { it.id in grouped }
                    val loose = state.sessions.size - inWorkspace
                    Text(
                        if (loose > 0) {
                            "工作区：${state.workspaces.first().title} · $inWorkspace 个对话 · 未分组 $loose"
                        } else {
                            "工作区：${state.workspaces.first().title} · ${state.sessions.size} 个对话"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} · 协议 DSH API Gateway",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text("断开连接", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Compact dropdown used for the three appearance settings so they fit on a single row. */
@Composable
private fun SettingsDropdown(
    caption: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(caption, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true },
        ) {
            Row(
                Modifier.padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 12.sp) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun PickerChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------- drawer

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawer(
    state: ChatUiState,
    onNewSession: () -> Unit,
    onSelect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSessionQueryChange: (String) -> Unit,
    onOrderByChange: (String) -> Unit,
    onArchivedFilterChange: (String) -> Unit,
    onSessionArchiveToggle: (String, Boolean) -> Unit,
    onSessionRename: (String, String) -> Unit,
) {
    var searchOpen by remember { mutableStateOf(state.sessionQuery.isNotEmpty()) }
    var viewMenuOpen by remember { mutableStateOf(false) }
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SessionRow?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    // The desktop shows SESSION_WATERMARK_BASE rows and reveals the same amount per click.
    // The phone keeps 10 rows and adds 5 each time, resetting whenever the view changes.
    val pageBase = 10
    val pageStep = 5
    var expanded by remember(state.sessionQuery, state.archivedFilter, state.orderBy) {
        mutableStateOf(0)
    }
    val visibleRows = state.sessionList.take(pageBase + expanded)
    val hiddenCount = state.sessionList.size - visibleRows.size

    Column(Modifier.fillMaxSize()) {
        // Desktop header: title on the left, search / view options / "+" on the same row.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "DSH",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (state.connected) "已连接 · ${state.urlDraft}" else "未连接",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) onSessionQueryChange("")
                },
            ) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = "搜索会话",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box {
                IconButton(onClick = { viewMenuOpen = true }) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = "视图选项",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }) {
                    DrawerMenuLabel("排序方式")
                    listOf("updated" to "最近更新", "manual" to "手动排序").forEach { (value, label) ->
                        DrawerMenuItem(
                            label = label,
                            selected = state.orderBy == value,
                        ) {
                            viewMenuOpen = false
                            onOrderByChange(value)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    DrawerMenuLabel("筛选会话")
                    listOf(
                        "default" to "隐藏已归档",
                        "show" to "全部对话（显示已归档）",
                        "only" to "仅显示已归档",
                    ).forEach { (value, label) ->
                        DrawerMenuItem(
                            label = label,
                            selected = state.archivedFilter == value,
                        ) {
                            viewMenuOpen = false
                            onArchivedFilterChange(value)
                        }
                    }
                }
            }
            IconButton(enabled = state.connected, onClick = onNewSession) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "新建对话",
                    // Same tint as the search/view buttons on its left: the accent colour made the
                    // "+" look like a different kind of control.
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (state.connected) 1f else 0.38f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = state.sessionQuery,
                onValueChange = onSessionQueryChange,
                placeholder = { Text("搜索会话名称", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
                trailingIcon = {
                    if (state.sessionQuery.isNotEmpty()) {
                        IconButton(onClick = { onSessionQueryChange("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清除搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
            )
        }
        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (state.sessionList.isEmpty()) {
                item(key = "__sessions_empty__") {
                    Text(
                        text = when {
                            state.sessionQuery.isNotBlank() -> "无匹配结果"
                            state.archivedFilter == "only" -> "暂无已归档会话"
                            else -> "暂无会话"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            }
            items(visibleRows, key = { it.id }) { row ->
                val active = row.id == state.sessionId
                Box {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 1.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .combinedClickable(
                                onClick = { onSelect(row.id) },
                                onLongClick = { rowMenuFor = row.id },
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.title,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.running) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF66BB6A), RoundedCornerShape(50)),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = relativeTime(row.updatedAt),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    // Desktop row menu: 归档会话 / 取消归档 / 重命名.
                    DropdownMenu(
                        expanded = rowMenuFor == row.id,
                        onDismissRequest = { rowMenuFor = null },
                    ) {
                        DrawerMenuItem(label = "重命名", selected = false) {
                            rowMenuFor = null
                            renameDraft = row.title
                            renameTarget = row
                        }
                        DrawerMenuItem(
                            label = if (row.id in state.archived) "取消归档" else "归档会话",
                            selected = false,
                        ) {
                            rowMenuFor = null
                            onSessionArchiveToggle(row.id, row.id !in state.archived)
                        }
                    }
                }
            }
            if (hiddenCount > 0) {
                item(key = "__expand_sessions__") {
                    TextButton(
                        onClick = { expanded += pageStep },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("展开其余 $hiddenCount 个会话", fontSize = 11.sp)
                    }
                }
            } else if (expanded > 0) {
                item(key = "__collapse_sessions__") {
                    TextButton(
                        onClick = { expanded = 0 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("收起", fontSize = 11.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable(onClick = onDisconnect)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("断开连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话", fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameDraft.isNotBlank() && renameDraft.trim() != target.title,
                    onClick = {
                        onSessionRename(target.id, renameDraft.trim())
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }
}

/** Small caption row inside a drawer dropdown, mirroring the desktop menu labels. */
@Composable
private fun DrawerMenuLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** One selectable row inside a drawer dropdown, with a check mark on the current value. */
@Composable
private fun DrawerMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 12.sp) },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        },
    )
}
// -------------------------------------------------------------------- sheets

@Composable
private fun ModelSheet(state: ChatUiState, onPick: (ModelOption) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // 134 models across 7 providers: the sheet must scroll, or only the first groups show.
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            "选择模型 · ${state.models.size} 个",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (state.models.isEmpty()) {
            Text(
                "没有读到模型目录",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        val grouped = state.models.groupBy { it.groupName }
        grouped.forEach { (groupName, models) ->
            Text(
                groupName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
            )
            models.forEach { option ->
                val active = option.modelName == state.modelLabel
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(option) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.modelName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            option.provider,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (active) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSheet(state: ChatUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(
            "权限模式",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        state.permissions.forEach { option ->
            val active = option.value == state.permissionCurrent
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.name,
                    fontSize = 14.sp,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Text(
                        "当前",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "DSH 目前没有向外开放切换权限的接口（唯一可读的是 permissionPresets/catalog，\n" +
                "session/create 也不接受权限参数），所以这里只展示当前值。要改请在电脑端切换。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

// ------------------------------------------------------------------ messages

/** Turn navigator: the reliable way to jump — every turn with its prompt, one tap each. */
@Composable
private fun TurnSheet(
    turns: List<TurnEntry>,
    messages: List<ChatMessage>,
    onPick: (Int) -> Unit,
) {
    val targets = remember(turns, messages) {
        turns.map { turn ->
            messages.indexOfFirst { msg ->
                val s = msg.key.substringAfterLast('#', "").toIntOrNull()
                s != null && s >= turn.seq
            }.let { if (it < 0) messages.lastIndex.coerceAtLeast(0) else it }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            "回合导航 · ${turns.size} 个回合",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(turns.size) { i ->
                val turn = turns[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(targets[i]) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "${turn.index}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(
                        turn.prompt.ifBlank { "（无提示词）" },
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Desktop-style turn rail: one tick per turn down the right edge.
 *
 * Tap a tick to jump, or press and slide up/down to scrub through turns; the tick under the
 * finger grows and brightens, and a counter shows which turn is selected.
 */
@Composable
private fun TurnRail(
    turns: List<Int>,
    messages: List<ChatMessage>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets = remember(turns, messages) {
        turns.map { seq ->
            messages.indexOfFirst { msg ->
                val raw = msg.key.substringAfterLast('#', "")
                val s = raw.toIntOrNull()
                s != null && s >= seq
            }.let { if (it < 0) messages.lastIndex.coerceAtLeast(0) else it }
        }
    }
    var selected by remember { mutableIntStateOf(-1) }
    var scrubbing by remember { mutableStateOf(false) }

    fun indexAt(y: Float, height: Float): Int {
        if (targets.isEmpty() || height <= 0f) return -1
        return ((y / height) * targets.size).toInt().coerceIn(0, targets.size - 1)
    }

    fun pick(index: Int) {
        if (index in targets.indices && index != selected) {
            selected = index
            onJump(targets[index])
        }
    }

    Box(modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(24.dp)
                .pointerInput(targets) {
                    detectTapGestures { offset -> pick(indexAt(offset.y, size.height.toFloat())) }
                }
                .pointerInput(targets) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            scrubbing = true
                            pick(indexAt(offset.y, size.height.toFloat()))
                        },
                        onDragEnd = { scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        pick(indexAt(change.position.y, size.height.toFloat()))
                    }
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            targets.forEachIndexed { i, _ ->
                val active = i == selected
                Box(
                    Modifier
                        .padding(vertical = 2.5.dp)
                        .width(if (active) 16.dp else 10.dp)
                        .height(if (active) 6.dp else 3.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        if (scrubbing && selected in targets.indices) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(end = 22.dp),
            ) {
                Text(
                    "回合 ${selected + 1}/${targets.size}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (connected) "说点什么，让电脑上的 agent 开始干活" else "连接已断开",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, fontSize: TextUnit, fontChoice: FontChoice) {
    val family = familyOf(fontChoice)
    // Long-press to select, then the system toolbar offers copy/cut/share — the desktop GUI
    // lets you select any text in the transcript, so the phone must too.
    SelectionContainer {
        when (message.role) {
            Role.SYSTEM -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    message.text,
                    fontSize = (fontSize.value * 0.8f).sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        message.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = fontSize,
                        lineHeight = (fontSize.value * 1.5f).sp,
                        fontFamily = family,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Assistant prose is Markdown, exactly like the desktop GUI renders it.
            Role.ASSISTANT -> Box(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                MarkdownView(
                    source = message.text,
                    fontSize = fontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                    accent = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ composer

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    running: Boolean,
    connected: Boolean,
    modelLabel: String,
    permission: String,
    onModelClick: () -> Unit,
    onPermissionClick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    approvals: List<ApprovalRequest>,
    onApproval: (ApprovalRequest, Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            // Tool approvals interrupt the turn: answer before anything else.
            approvals.forEach { approval ->
                ApprovalCard(approval = approval, onAnswer = onApproval)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(
                    text = modelLabel.ifBlank { "模型" },
                    onClick = onModelClick,
                    enabled = connected,
                )
                Chip(
                    text = permission.ifBlank { "权限" },
                    onClick = onPermissionClick,
                    enabled = connected,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("给电脑上的 agent 发消息…", fontSize = 13.sp) },
                    maxLines = 5,
                    enabled = connected,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                Spacer(Modifier.width(8.dp))
                if (running) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = "中断")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = connected && draft.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ApprovalRequest, onAnswer: (ApprovalRequest, Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "需要你确认",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                approval.toolName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (approval.reason.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    approval.reason,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAnswer(approval, true) },
                    modifier = Modifier.weight(1f),
                ) { Text("允许一次", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { onAnswer(approval, false) },
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit, enabled: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .height(28.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val delta = System.currentTimeMillis() - epochMs
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> "${delta / 60_000} 分钟前"
        delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
        delta < 7L * 86_400_000 -> "${delta / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
