package com.dsh.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import com.dsh.remote.ui.ChatScreen
import com.dsh.remote.ui.ChatViewModel
import com.dsh.remote.ui.DshTheme
import com.dsh.remote.ui.Screen
import com.dsh.remote.ui.HelpScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.dsh.remote.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    /** Fires on Wi-Fi/LTE handover, which changes the phone's public IP and kills the mux. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModel.reconnectIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android freezes backgrounded apps, which kills the mux socket. Whenever the app comes
        // back to the foreground, rebuild the link if it is gone 閳?silently, keeping the session.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.reconnectIfNeeded()
            }
        )
        // The carrier reassigns the public IP on cell handover (observed 218.x -> 101.x mid-session),
        // which drops every TCP socket. Rebuild as soon as a network is available again instead of
        // waiting for the user to notice.
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback)
        }
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            // QR pairing: the bridge shows a one-time code, the phone redeems it for a cookie.
            // ZXing (not ML Kit) so HMS-only phones without Google Play services can scan too.
            val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                result.contents?.let { viewModel.pairFromQr(it) }
            }
            val launchScan: () -> Unit = {
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("对准电脑上的配对二维码")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    },
                )
            }

            // Back from help / the address screen returns instead of exiting the app.
            BackHandler(enabled = state.screen != Screen.Chat) {
                if (!viewModel.backToChat()) finish()
            }

            DshTheme(
                mode = state.theme,
                fontChoice = state.fontChoice,
                fontScale = state.fontScale,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (state.screen) {
                        Screen.Setup -> SetupScreen(
                            mode = state.mode,
                            urlDraft = state.urlDraft,
                            tokenDraft = state.tokenDraft,
                            cookieDraft = state.cookieDraft,
                            customHistory = state.customHistory,
                            connecting = state.connecting,
                            error = state.error,
                            onModeChange = viewModel::chooseMode,
                            onUrlChange = viewModel::updateUrl,
                            onTokenChange = viewModel::updateToken,
                            onCookieChange = viewModel::updateCookie,
                            onPickHistory = viewModel::pickCustomUrl,
                            onDeleteHistory = viewModel::deleteCustomUrl,
                            onConnect = { viewModel.connect(state.mode) },
                            onDismissError = viewModel::dismissError,
                            onOpenHelp = viewModel::openHelp,
                            onScanQr = launchScan,
                            onPairManual = viewModel::pairFromCode,
                        )

                        Screen.Help -> HelpScreen(onBack = { viewModel.backToChat() })

                        Screen.Chat -> ChatScreen(
                            state = state,
                            onSend = viewModel::send,
                            onStop = viewModel::stop,
                            onNewSession = viewModel::newSession,
                            onSelectSession = viewModel::selectSession,
                            onSelectModel = viewModel::selectModel,
                            onSetTheme = viewModel::setTheme,
                            onSetFont = viewModel::setFontChoice,
                            onSetScale = viewModel::setFontScale,
                            onZoom = viewModel::zoomBy,
                            onChangeMode = viewModel::selectMode,
                            onOpenSetup = viewModel::openSetup,
                            onUrlChange = viewModel::updateUrl,
                            onTokenChange = viewModel::updateToken,
                            onCookieChange = viewModel::updateCookie,
                            onPickHistory = viewModel::pickCustomUrl,
                            onDeleteHistory = viewModel::deleteCustomUrl,
                            onConnect = { viewModel.connect(state.mode) },
                            onDisconnect = viewModel::disconnect,
                            onExit = { finish() },
                            onApproval = viewModel::respondApproval,
                            onDismissError = viewModel::dismissError,
                            onLoadEarlier = viewModel::loadEarlier,
                            onSessionQueryChange = viewModel::updateSessionQuery,
                            onOrderByChange = viewModel::setOrderBy,
                            onArchivedFilterChange = viewModel::setArchivedFilter,
                            onSessionArchiveToggle = viewModel::setSessionArchived,
                            onSessionRename = viewModel::renameSession,
                            onOpenHelp = viewModel::openHelp,
                            onScanQr = launchScan,
                            onPairManual = viewModel::pairFromCode,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
        super.onDestroy()
    }
}
