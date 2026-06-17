package com.example.pullyluncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.example.pullyluncher.data.UiConfigPrefs
import com.example.pullyluncher.model.LauncherUiConfig
import com.example.pullyluncher.ui.theme.PullLauncherScreen
import com.example.pullyluncher.ui.theme.SettingsScreen
import com.example.pullyluncher.ui.theme.PullyLuncherTheme

class MainActivity : ComponentActivity() {

    // オーバーレイ権限リクエスト後の状態を Compose に反映するための State
    private val hasOverlayPermission  = mutableStateOf(false)
    private val isOverlayRunning      = mutableStateOf(false)
    private val historyRefreshNonce   = mutableIntStateOf(0)

    // config を Activity レベルで保持することで startOverlayService() から nodeCount を参照できる
    private val config = mutableStateOf(LauncherUiConfig())

    // オーバーレイ権限設定画面から戻ったときに状態を更新
    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshOverlayState() }

    // ── ライフサイクル ───────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 永続化済みの nodeCount / colorPreset をロード
        config.value = UiConfigPrefs.load(this)
        LauncherRepository.config = config.value
        refreshOverlayState()
        enableEdgeToEdge()

        setContent {
            PullyLuncherTheme {
                // アプリを開いたら直接設定画面を表示する
                SettingsScreen(
                    config               = config.value,
                    onConfigChange       = {
                        config.value = it
                        LauncherRepository.config = it
                        UiConfigPrefs.save(this@MainActivity, it)
                    },
                    onClose              = { finish() },
                    hasOverlayPermission = hasOverlayPermission.value,
                    isOverlayRunning     = isOverlayRunning.value,
                    onRequestPermission  = ::requestOverlayPermission,
                    onStartOverlay       = ::startOverlayService,
                    onStopOverlay        = ::stopOverlayService
                )
            }
        }
    }

    /** フォアグラウンドから戻ったとき（権限付与・サービス停止など）に状態を再チェック */
    override fun onResume() {
        super.onResume()
        refreshOverlayState()
        historyRefreshNonce.intValue++
    }

    // ── オーバーレイ制御 ─────────────────────────────────────────

    private fun refreshOverlayState() {
        hasOverlayPermission.value = Settings.canDrawOverlays(this)
        isOverlayRunning.value     = OverlayService.isRunning
    }

    private fun requestOverlayPermission() {
        overlayPermLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun startOverlayService() {
        LauncherRepository.config = config.value   // サービス起動前に最新 config を反映
        startForegroundService(Intent(this, OverlayService::class.java))
        isOverlayRunning.value = true
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        isOverlayRunning.value = false
    }
}

// ── Composable ──────────────────────────────────────────────────

@Composable
private fun PullyLuncherApp(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    historyRefreshNonce: Int,
    hasOverlayPermission: Boolean,
    isOverlayRunning: Boolean,
    onRequestPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    var showSettings    by remember { mutableStateOf(false) }
    var settingsVersion by remember { mutableIntStateOf(0) }

    if (showSettings) {
        SettingsScreen(
            config               = config,
            onConfigChange       = onConfigChange,
            onClose              = {
                showSettings = false
                settingsVersion++
            },
            hasOverlayPermission = hasOverlayPermission,
            isOverlayRunning     = isOverlayRunning,
            onRequestPermission  = onRequestPermission,
            onStartOverlay       = onStartOverlay,
            onStopOverlay        = onStopOverlay
        )
    } else {
        key(settingsVersion) {
            PullLauncherScreen(
                config         = config,
                onOpenSettings = { showSettings = true },
                refreshNonce   = historyRefreshNonce
            )
        }
    }
}
