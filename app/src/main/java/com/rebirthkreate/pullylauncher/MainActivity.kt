package com.rebirthkreate.pullylauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.rebirthkreate.pullylauncher.BuildConfig
import com.rebirthkreate.pullylauncher.data.UiConfigPrefs
import com.rebirthkreate.pullylauncher.model.LauncherUiConfig
import com.rebirthkreate.pullylauncher.ui.theme.PullLauncherScreen
import com.rebirthkreate.pullylauncher.ui.theme.SettingsScreen
import com.rebirthkreate.pullylauncher.ui.theme.PullyLuncherTheme

private const val STARTUP_TAG = "PullyStartup"

class MainActivity : ComponentActivity() {

    private val hasOverlayPermission  = mutableStateOf(false)
    private val isOverlayRunning      = mutableStateOf(false)
    private val historyRefreshNonce   = mutableIntStateOf(0)
    private val config                = mutableStateOf(LauncherUiConfig())

    /** フローティング開始前に通知権限の説明ダイアログを表示するか */
    private val showNotifRationale    = mutableStateOf(false)

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshOverlayState() }

    /** POST_NOTIFICATIONS のランタイム権限リクエスト。結果によらずサービスを起動する。 */
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { doStartOverlayService() }

    // ── ライフサイクル ───────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "MainActivity onCreate overlay=${Settings.canDrawOverlays(this)} service=${OverlayService.isRunning}")
        config.value = UiConfigPrefs.load(this)
        LauncherRepository.config = config.value
        refreshOverlayState()
        enableEdgeToEdge()

        setContent {
            PullyLuncherTheme {
                // 通知権限説明ダイアログ（フローティング開始ボタン押下時にのみ表示）
                if (showNotifRationale.value) {
                    AlertDialog(
                        onDismissRequest = {
                            showNotifRationale.value = false
                            doStartOverlayService()
                        },
                        title = { Text("通知の許可") },
                        text  = { Text("Pullyを他のアプリ使用中も動作させるため、実行中であることを示す通知を表示します。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showNotifRationale.value = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    doStartOverlayService()
                                }
                            }) { Text("通知を許可する") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showNotifRationale.value = false
                                doStartOverlayService()
                            }) { Text("今は許可しない") }
                        }
                    )
                }

                PullyLuncherApp(
                    config               = config.value,
                    onConfigChange       = {
                        config.value = it
                        LauncherRepository.config = it
                        UiConfigPrefs.save(this@MainActivity, it)
                    },
                    historyRefreshNonce  = historyRefreshNonce.intValue,
                    hasOverlayPermission = hasOverlayPermission.value,
                    isOverlayRunning     = isOverlayRunning.value,
                    onRequestPermission  = ::requestOverlayPermission,
                    onStartOverlay       = ::startOverlayService,
                    onStopOverlay        = ::stopOverlayService
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "MainActivity onStart")
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "MainActivity onResume overlay=${Settings.canDrawOverlays(this)} service=${OverlayService.isRunning} appsLoaded=${LauncherRepository.allApps.size}")
        refreshOverlayState()
        historyRefreshNonce.intValue++
        // フォールバックリフレッシュ: OverlayService が停止中にパッケージ変更があった場合に対応。
        // allApps が未ロードの場合は LaunchedEffect が loadAll を呼ぶため二重実行を避ける。
        if (LauncherRepository.allApps.isNotEmpty()) {
            LauncherRepository.scheduleAppsRefresh(this, "on_resume")
        }
    }

    override fun onPause() {
        super.onPause()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "MainActivity onPause finishing=$isFinishing changingConfigs=$isChangingConfigurations")
    }

    override fun onStop() {
        super.onStop()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "MainActivity onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "MainActivity onDestroy finishing=$isFinishing changingConfigs=$isChangingConfigurations")
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

    /**
     * フローティング開始。Android 13+ で通知権限が未付与の場合は説明ダイアログを先に表示する。
     * 既に許可済みの場合はダイアログをスキップして即時起動する。
     */
    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                showNotifRationale.value = true
                return
            }
        }
        doStartOverlayService()
    }

    private fun doStartOverlayService() {
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "doStartOverlayService called")
        try {
            LauncherRepository.config = config.value
            startForegroundService(Intent(this, OverlayService::class.java))
            isOverlayRunning.value = true
            if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "doStartOverlayService succeeded")
        } catch (e: Exception) {
            Log.e(STARTUP_TAG, "doStartOverlayService failed: ${e.javaClass.simpleName} ${e.message}", e)
        }
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
