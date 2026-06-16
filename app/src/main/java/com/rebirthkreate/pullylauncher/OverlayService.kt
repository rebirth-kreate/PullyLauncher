package com.rebirthkreate.pullylauncher

import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.rebirthkreate.pullylauncher.BuildConfig
import com.rebirthkreate.pullylauncher.data.UiConfigPrefs
import com.rebirthkreate.pullylauncher.data.UsageHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * フローティング UI を管理するフォアグラウンドサービス。
 *
 * ── 2ウィンドウ構成 ────────────────────────────────────────────────────
 *
 *   [touchView] / touch Window
 *     ・サイズ: ボール直径のみ（buttonRadiusPx × 2.4 の正方形）
 *     ・フラグ: FLAG_NOT_FOCUSABLE（タッチ可能）
 *     ・役割: タッチイベントの受付のみ。描画なし。
 *     ・ボールが移動すると windowManager.updateViewLayout でリアルタイム追従。
 *
 *   [drawView] / draw Window
 *     ・サイズ: MATCH_PARENT（全画面）
 *     ・フラグ: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
 *     ・役割: ボール・ブロブ・ノードの描画のみ。タッチを一切受け取らない。
 *     ・座標は touchView.centerX / touchView.centerY を参照して描く。
 *
 * ── 座標の基準 ─────────────────────────────────────────────────────────
 *   touch Window の中心座標 (savedCenterX, savedCenterY) が唯一の真実。
 *   draw Window はその座標を touchView 参照経由で読み、画面全体に描画する。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var touchView: OverlayTouchView? = null
    private var drawView: OverlayExpandView? = null

    /** draw Window の LayoutParams。secureOverlay 設定変更時に updateViewLayout で更新する。 */
    private var drawLayoutParams: WindowManager.LayoutParams? = null
    /** touch Window の LayoutParams。移動時に updateViewLayout で更新する。 */
    private var touchParams: WindowManager.LayoutParams? = null
    /** touch Window のサイズ（正方形の一辺）。 */
    private var touchWindowSize = 0

    /** ボール位置を保存し、View 再生成時に復元する */
    private var savedCenterX = 0f
    private var savedCenterY = 0f

    @Suppress("DEPRECATION")
    private var vibrator: Vibrator? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var packageReceiver: BroadcastReceiver? = null

    companion object {
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID      = "pully_overlay_channel"
        private const val NOTIF_ID        = 1001
        private const val STARTUP_TAG     = "PullyStartup"
        private const val VISIBILITY_TAG  = "PullyVisibility"
        private const val APPS_TAG        = "PullyApps"

        /** UsageStats ポーリング間隔（画面点灯中）。Galaxy 実機テスト後に調整可。 */
        const val POLL_INTERVAL_MS = 850L

        private val PACKAGE_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REPLACED
        )
    }

    // ── ライフサイクル ────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "OverlayService onStartCommand flags=$flags appsLoaded=${LauncherRepository.allApps.size}")
        if (LauncherRepository.allApps.isEmpty()) {
            LauncherRepository.loadAppsIfNeeded(this)
        } else {
            // サービス再起動時のフォールバックリフレッシュ（停止中にパッケージ変更があった場合に対応）
            LauncherRepository.scheduleAppsRefresh(this, "service_restart")
        }
        if (touchView == null) addOverlayViews()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "OverlayService onCreate")
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        LauncherRepository.config = UiConfigPrefs.load(this)
        LauncherRepository.loadAppsIfNeeded(this)

        // 初期ボール位置: 左上から 16dp・200dp の位置にボール中心を置く
        val density = resources.displayMetrics.density
        val r       = LauncherRepository.config.buttonRadiusPx
        savedCenterX = (16 * density) + r
        savedCenterY = (200 * density) + r

        serviceScope.launch {
            LauncherRepository.configFlow.collect { onConfigUpdated() }
        }

        LauncherRepository.onIconsLoaded = { drawView?.invalidate() }

        startForegroundWithNotification()
        addOverlayViews()
        startForegroundPolling()
        registerPackageReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "OverlayService onDestroy")
        isRunning = false
        LauncherRepository.onIconsLoaded = null
        unregisterPackageReceiver()
        serviceScope.cancel()
        removeOverlayViews()
    }

    // ── 2ウィンドウ管理 ──────────────────────────────────────────

    private fun addOverlayViews() {
        if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "addOverlayViews start")
        try {
            val cfg = LauncherRepository.config
            val secureFlag = if (cfg.secureOverlay) WindowManager.LayoutParams.FLAG_SECURE else 0

            // ── draw Window（全画面・FLAG_NOT_TOUCHABLE）──────────────────
            val draw = OverlayExpandView(this)
            val drawParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        secureFlag,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            drawLayoutParams = drawParams
            drawView = draw
            windowManager.addView(draw, drawParams)

            // ── touch Window（ボールサイズ・タッチ可能）──────────────────
            // 視覚ボール半径と完全一致させる（倍率なし）
            val touchRadius = cfg.buttonRadiusPx
            val size = (touchRadius * 2f).toInt()
            touchWindowSize = size

            val touch = OverlayTouchView(this, savedCenterX, savedCenterY)
            val tParams = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or secureFlag,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (savedCenterX - touchRadius).toInt()
                y = (savedCenterY - touchRadius).toInt()
            }
            touchParams = tParams

            // コールバック配線
            touch.onBallMoved = { cx, cy ->
                savedCenterX = cx
                savedCenterY = cy
                moveTouchWindow(cx, cy)
                draw.invalidate()
            }
            touch.onPositionChanged = { cx, cy ->
                savedCenterX = cx
                savedCenterY = cy
            }
            touch.onMoveStateChanged = { isMoving ->
                // MOVING 中はボールが BALL_MOVING_SCALE 倍に拡大するので touch Window も合わせる
                val newRadius = cfg.buttonRadiusPx * (if (isMoving) BALL_MOVING_SCALE else 1.0f)
                resizeTouchWindow(newRadius)
            }
            touch.onGoHome         = { goHome() }
            touch.onLaunchApp      = { pkg -> launchApp(pkg) }
            touch.onHapticFeedback = { performHaptic() }
            touch.onDrawInvalidate = { draw.invalidate() }

            // draw View に touch View を渡す（onDraw 内で状態を読む）
            draw.touchView = touch

            touchView = touch
            windowManager.addView(touch, tParams)

            applyBallVisibility()
            if (BuildConfig.DEBUG) Log.d(STARTUP_TAG, "addOverlayViews done")
        } catch (e: SecurityException) {
            Log.e(STARTUP_TAG, "addOverlayViews: SYSTEM_ALERT_WINDOW not granted", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(STARTUP_TAG, "addOverlayViews failed: ${e.javaClass.simpleName}", e)
            stopSelf()
        }
    }

    private fun removeOverlayViews() {
        // draw Window を先に外す（touch Window への参照を切るため）
        drawView?.let {
            it.touchView = null
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        drawView = null

        touchView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        touchView = null
        touchParams = null
        drawLayoutParams = null
    }

    /** touch Window の位置をボール中心に合わせる。 */
    private fun moveTouchWindow(cx: Float, cy: Float) {
        val params = touchParams ?: return
        val half = touchWindowSize / 2f
        params.x = (cx - half).toInt()
        params.y = (cy - half).toInt()
        try {
            touchView?.let { windowManager.updateViewLayout(it, params) }
        } catch (_: Exception) {}
    }

    /**
     * touch Window のサイズを視覚ボール半径に合わせてリサイズする。
     * MOVING 開始/終了時に呼ばれる。位置も同時に更新して中心がズレないようにする。
     */
    private fun resizeTouchWindow(radius: Float) {
        val params = touchParams ?: return
        val newSize = (radius * 2f).toInt()
        if (touchWindowSize == newSize) return
        touchWindowSize = newSize
        params.width  = newSize
        params.height = newSize
        params.x = (savedCenterX - radius).toInt()
        params.y = (savedCenterY - radius).toInt()
        try {
            touchView?.let { windowManager.updateViewLayout(it, params) }
        } catch (_: Exception) {}
    }

    // ── UsageStats ポーリング ────────────────────────────────────

    /**
     * UsageStats の MOVE_TO_FOREGROUND イベントを POLL_INTERVAL_MS ごとに確認して
     * hiddenPackages の可視判定を行う。
     *
     * ・PACKAGE_USAGE_STATS 権限がなければスキップ（非表示機能は動作しないが起動は継続）
     * ・画面消灯中（PowerManager.isInteractive=false）はスキップして消費電力を抑える
     * ・同じパッケージ名が続く場合は表示更新を行わない
     */
    private fun startForegroundPolling() {
        val pm = getSystemService(PowerManager::class.java)
        serviceScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (!pm.isInteractive) continue
                if (!UsageHistoryRepository.hasPermission(this@OverlayService)) continue
                val pkg = withContext(Dispatchers.IO) {
                    UsageHistoryRepository.getForegroundPackage(this@OverlayService)
                } ?: continue
                if (pkg == LauncherRepository.currentForegroundPackage) continue
                LauncherRepository.currentForegroundPackage = pkg
                applyBallVisibility()
            }
        }
    }

    // ── ボール可視制御 ─────────────────────────────────────────────

    private fun applyBallVisibility() {
        val hidden    = LauncherRepository.config.hiddenPackages
        val fg        = LauncherRepository.currentForegroundPackage
        val hasPerms  = UsageHistoryRepository.hasPermission(this)
        val shouldHide = hidden.isNotEmpty() && fg != null && fg in hidden
        val newVis    = if (shouldHide) View.INVISIBLE else View.VISIBLE

        if (BuildConfig.DEBUG) {
            Log.d(VISIBILITY_TAG, "[UsageStats] fg=$fg")
            Log.d(VISIBILITY_TAG, "hidden=${hidden.isNotEmpty()} match=$shouldHide result=${if (shouldHide) "HIDE" else "SHOW"} permission=$hasPerms")
        }

        drawView?.let  { if (it.visibility != newVis) it.visibility = newVis }
        touchView?.let { if (it.visibility != newVis) it.visibility = newVis }
    }

    /** secureOverlay 設定に合わせて両 Window の FLAG_SECURE を動的に更新する。 */
    private fun applySecureFlag() {
        val flag   = WindowManager.LayoutParams.FLAG_SECURE
        val secure = LauncherRepository.config.secureOverlay

        val dv = drawView
        val dp = drawLayoutParams
        if (dv != null && dp != null) {
            dp.flags = if (secure) dp.flags or flag else dp.flags and flag.inv()
            try {
                windowManager.updateViewLayout(dv, dp)
            } catch (e: IllegalArgumentException) {
                if (BuildConfig.DEBUG) Log.w(VISIBILITY_TAG, "applySecureFlag: drawView not attached", e)
            }
        }

        val tv = touchView
        val tp = touchParams
        if (tv != null && tp != null) {
            tp.flags = if (secure) tp.flags or flag else tp.flags and flag.inv()
            try {
                windowManager.updateViewLayout(tv, tp)
            } catch (e: IllegalArgumentException) {
                if (BuildConfig.DEBUG) Log.w(VISIBILITY_TAG, "applySecureFlag: touchView not attached", e)
            }
        }
    }

    /**
     * configFlow 変化時。
     * View 自身が configFlow を collect して invalidate() するため、
     * サービス側は hiddenPackages の可視制御と FLAG_SECURE の更新のみ行う。
     */
    private fun onConfigUpdated() {
        applyBallVisibility()
        applySecureFlag()
    }

    // ── アプリ起動 / ホーム ───────────────────────────────────────

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun launchApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    // ── 触覚フィードバック ────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun performHaptic() {
        val vib = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vib.vibrate(15)
        }
    }

    // ── パッケージ変更レシーバー ──────────────────────────────────

    private fun registerPackageReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                // protected broadcast 以外は無視（action が対象外の場合もガード）
                if (action !in PACKAGE_ACTIONS) return
                val pkg = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

                if (BuildConfig.DEBUG) {
                    Log.d(APPS_TAG, "package event action=$action package=$pkg replacing=$replacing")
                }

                when (action) {
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (replacing) {
                            // アプリ更新の旧バージョン削除: アイコンキャッシュのみ無効化し ADDED を待つ
                            LauncherRepository.invalidateIconCacheFor(this@OverlayService, pkg)
                        } else {
                            // 真のアンインストール: allApps / pinned / hidden から完全除去
                            LauncherRepository.removePackage(this@OverlayService, pkg)
                        }
                    }
                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (replacing) {
                            // 更新完了時: REMOVED で無効化済みだが念のため再度無効化する
                            LauncherRepository.invalidateIconCacheFor(this@OverlayService, pkg)
                        }
                        LauncherRepository.scheduleAppsRefresh(this@OverlayService, "package_event:$action")
                    }
                    Intent.ACTION_PACKAGE_CHANGED,
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        // コンポーネント変更: ラベル・アイコンが変わっている可能性があるためキャッシュを無効化
                        LauncherRepository.invalidateIconCacheFor(this@OverlayService, pkg)
                        LauncherRepository.scheduleAppsRefresh(this@OverlayService, "package_event:$action")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        // RECEIVER_NOT_EXPORTED を使用する理由:
        //   - Android ドキュメントより「システムブロードキャストはフラグに関わらず常に配信される」
        //   - protected broadcast (PACKAGE_ADDED 等) はシステムプロセスのみが送信可能
        //   - 外部アプリからの偽装ブロードキャストを拒否できるため EXPORTED より安全
        //   - ContextCompat が API バージョン分岐を内部で処理する（API < 33 ではフラグなし呼出し）
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        packageReceiver = receiver
    }

    private fun unregisterPackageReceiver() {
        try {
            packageReceiver?.let { unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) {}
        packageReceiver = null
    }

    // ── フォアグラウンド通知 ──────────────────────────────────────

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PullyLauncher オーバーレイ",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PullyLauncher")
            .setContentText("フローティングボタンが表示中です")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
