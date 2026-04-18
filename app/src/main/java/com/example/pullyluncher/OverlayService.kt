package com.example.pullyluncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.pullyluncher.data.UiConfigPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    companion object {
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "pully_overlay_channel"
        private const val NOTIF_ID   = 1001
    }

    // ── ライフサイクル ────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LauncherRepository.loadAppsIfNeeded(this)
        if (touchView == null) addOverlayViews()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
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

        LauncherRepository.onForegroundChanged = { applyBallVisibility() }
        LauncherRepository.onIconsLoaded       = { drawView?.invalidate() }

        startForegroundWithNotification()
        addOverlayViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        LauncherRepository.onForegroundChanged = null
        LauncherRepository.onIconsLoaded       = null
        serviceScope.cancel()
        removeOverlayViews()
    }

    // ── 2ウィンドウ管理 ──────────────────────────────────────────

    private fun addOverlayViews() {
        val cfg = LauncherRepository.config

        // ── draw Window（全画面・FLAG_NOT_TOUCHABLE）──────────────────
        val draw = OverlayExpandView(this)
        val drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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

    // ── ボール可視制御 ─────────────────────────────────────────────

    private fun applyBallVisibility() {
        val hidden = LauncherRepository.config.hiddenPackages
        val fg     = LauncherRepository.currentForegroundPackage
        val newVis = if (hidden.isNotEmpty() && fg != null && fg in hidden)
            View.INVISIBLE else View.VISIBLE
        drawView?.let  { if (it.visibility != newVis) it.visibility = newVis }
        touchView?.let { if (it.visibility != newVis) it.visibility = newVis }
    }

    /**
     * configFlow 変化時。
     * View 自身が configFlow を collect して invalidate() するため、
     * サービス側は hiddenPackages の可視制御のみ行う。
     */
    private fun onConfigUpdated() {
        applyBallVisibility()
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

    // ── フォアグラウンド通知 ──────────────────────────────────────

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PullyLuncher オーバーレイ",
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
            .setContentTitle("PullyLuncher")
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
