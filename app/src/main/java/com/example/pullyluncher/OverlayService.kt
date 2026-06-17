package com.example.pullyluncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.pullyluncher.data.OverlayPositionPrefs
import com.example.pullyluncher.data.UiConfigPrefs
import com.example.pullyluncher.data.UsageHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * フローティング UI を管理するフォアグラウンドサービス。
 *
 * ── 2ウィンドウ構成 ────────────────────────────────────────────────────
 *
 *   [touchView] / touch Window
 *     ・サイズ: ボール直径のみ
 *     ・フラグ: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
 *     ・役割: タッチイベントの受付のみ。描画なし。
 *     ・非表示時は FLAG_NOT_TOUCHABLE を追加してタッチを完全に透過させる。
 *
 *   [drawView] / draw Window
 *     ・サイズ: MATCH_PARENT（全画面）
 *     ・フラグ: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
 *     ・役割: ボール・ブロブ・ノードの描画のみ。タッチを一切受け取らない。
 *     ・FLAG_SECURE は使用しない。
 *
 * ── 前面アプリ検知 ────────────────────────────────────────────────────
 *   UsageStatsManager.queryEvents() を 750ms ポーリングで使用。
 *   対象イベント: MOVE_TO_FOREGROUND のみ（value=1、ACTIVITY_RESUMED と同値）。
 *   value=19 の FOREGROUND_SERVICE_START は Activity の前面移動ではないため除外。
 *   カーソル方式: lastFgEventTimestampMs より新しいイベントだけを処理する。
 *   権限なし・画面消灯中はスキップ。イベントなしは現在のパッケージを維持。
 *
 * ── 非表示理由管理 ──────────────────────────────────────────────────
 *   HiddenReasons で複数の非表示理由を一元管理する。
 *   updateOverlayVisibility() が唯一の show/hide 適用関数。
 *   Animator 競合は visibilityGeneration トークンで防止する。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private var touchView: OverlayTouchView? = null
    private var drawView: OverlayExpandView? = null

    /** touch Window の LayoutParams。移動・リサイズ・フラグ変更時に updateViewLayout で使う。 */
    private var touchParams: WindowManager.LayoutParams? = null
    /** touch Window のサイズ（正方形の一辺）。 */
    private var touchWindowSize = 0

    private var savedCenterX = 0f
    private var savedCenterY = 0f

    @Suppress("DEPRECATION")
    private var vibrator: Vibrator? = null

    private var temporaryHideJob: Job? = null
    /** 撮影モード有効化時の 3 秒ディレイ Job。非表示前にキャンセルすれば復帰できる。 */
    private var captureHideJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // ── 非表示理由一元管理 ─────────────────────────────────────────
    private data class HiddenReasons(
        val hiddenApp: Boolean = false,
        val temporaryHide: Boolean = false,
        val captureMode: Boolean = false
    ) {
        val any get() = hiddenApp || temporaryHide || captureMode
    }
    private var hiddenReasons = HiddenReasons()

    /** Animator 競合防止トークン。applyHide/applyShow 呼び出しごとにインクリメントする。 */
    private var visibilityGeneration = 0

    // ── ポーリング状態 ────────────────────────────────────────────
    private var lastFgEventTimestampMs: Long = 0L
    private var lastPolledPkg: String? = null
    private var lastPolledHasPermission: Boolean? = null

    // ── ログ差分検出 ─────────────────────────────────────────────
    private var lastLogPkg: String? = null
    private var lastLogReasons: HiddenReasons? = null
    private var lastLogVis: String? = null
    private var lastLogTouchNotTouchable: Boolean? = null

    companion object {
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID                   = "pully_overlay_channel"
        private const val NOTIF_ID                     = 1001
        private const val FADE_DURATION_MS             = 150L
        private const val FOREGROUND_POLL_INTERVAL_MS  = 750L
        private const val INITIAL_LOOKBACK_MS          = 60_000L
        private const val POLL_LOOKBACK_MS             = 5_000L
        private const val CAPTURE_HIDE_DELAY_MS        = 3_000L

        const val ACTION_ENABLE_CAPTURE_MODE  = "com.example.pullyluncher.ENABLE_CAPTURE_MODE"
        const val ACTION_DISABLE_CAPTURE_MODE = "com.example.pullyluncher.DISABLE_CAPTURE_MODE"
    }

    // ── ライフサイクル ────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LauncherRepository.loadAppsIfNeeded(this)
        when (intent?.action) {
            ACTION_DISABLE_CAPTURE_MODE -> {
                // captureMode は永続化しないため save() 不要
                LauncherRepository.config = LauncherRepository.config.copy(captureMode = false)
                applyCaptureMode(false)
            }
            ACTION_ENABLE_CAPTURE_MODE -> {
                LauncherRepository.config = LauncherRepository.config.copy(captureMode = true)
                applyCaptureMode(true)
            }
            else -> {
                if (touchView == null) addOverlayViews()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        powerManager  = getSystemService(Context.POWER_SERVICE) as PowerManager

        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        LauncherRepository.config = UiConfigPrefs.load(this)
        LauncherRepository.loadAppsIfNeeded(this)

        val density  = resources.displayMetrics.density
        val r        = LauncherRepository.config.buttonRadiusPx
        val defaultX = (16 * density) + r
        val defaultY = (200 * density) + r
        val (restoredX, restoredY) = OverlayPositionPrefs.load(this, defaultX, defaultY)
        savedCenterX = restoredX
        savedCenterY = restoredY

        // captureMode は永続化しないためロード値は常に false。初期化不要。

        serviceScope.launch {
            LauncherRepository.configFlow.collect { onConfigUpdated() }
        }

        LauncherRepository.onIconsLoaded = { drawView?.invalidate() }

        serviceScope.launch {
            pollForegroundPackage()
            while (true) {
                delay(FOREGROUND_POLL_INTERVAL_MS)
                pollForegroundPackage()
            }
        }

        startForegroundWithNotification()
        addOverlayViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        LauncherRepository.onIconsLoaded = null
        serviceScope.cancel()
        removeOverlayViews()
    }

    // ── 前面アプリポーリング ──────────────────────────────────────

    /**
     * カーソル方式で前面アプリを取得し、LauncherRepository を更新する。
     *
     * ・lastFgEventTimestampMs == 0L のとき（初回・権限回復後）: INITIAL_LOOKBACK_MS（60秒）を使用。
     * ・通常ポーリング: POLL_LOOKBACK_MS（5秒）を上限とし、lastFgEventTimestampMs より新しいイベントのみ処理。
     * ・新規イベントなし: 現在の前面パッケージを維持（null へ戻さない）。
     * ・画面消灯中: スキップ。
     * ・権限なし: hiddenApp を false にリセットして updateOverlayVisibility() を呼ぶ。
     */
    private fun pollForegroundPackage() {
        if (!powerManager.isInteractive) return

        val hasPermission = UsageHistoryRepository.hasPermission(applicationContext)
        val permChanged   = hasPermission != lastPolledHasPermission
        lastPolledHasPermission = hasPermission

        if (!hasPermission) {
            if (permChanged) {
                LauncherRepository.currentForegroundPackage = null
                lastPolledPkg          = null
                lastFgEventTimestampMs = 0L
                hiddenReasons = hiddenReasons.copy(hiddenApp = false)
                updateOverlayVisibility()
            }
            return
        }

        val isInitial  = lastFgEventTimestampMs == 0L
        val lookbackMs = if (isInitial) INITIAL_LOOKBACK_MS else POLL_LOOKBACK_MS

        val result = UsageHistoryRepository.queryForegroundEvent(
            applicationContext,
            afterTimestampMs = lastFgEventTimestampMs,
            lookbackMs       = lookbackMs
        )

        if (result != null) {
            val (pkg, ts) = result
            lastFgEventTimestampMs = ts
            if (pkg != lastPolledPkg || permChanged) {
                val before    = LauncherRepository.currentForegroundPackage
                lastPolledPkg = pkg
                LauncherRepository.currentForegroundPackage = pkg
                logForegroundDetected(before, pkg, ts, isInitial)
                applyBallVisibility()
            }
        } else {
            if (permChanged) applyBallVisibility()
        }
    }

    // ── 2ウィンドウ管理 ──────────────────────────────────────────

    private fun addOverlayViews() {
        // ── draw Window（全画面・FLAG_NOT_TOUCHABLE・FLAG_SECURE なし）──────────
        val draw = OverlayExpandView(this)
        val dParams = WindowManager.LayoutParams(
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
        windowManager.addView(draw, dParams)

        // ── touch Window（ボールサイズ・タッチ可能）──────────────────────────
        val cfg = LauncherRepository.config
        val touchRadius = cfg.buttonRadiusPx
        val size        = (touchRadius * 2f).roundToInt()
        touchWindowSize = size

        val touch   = OverlayTouchView(this, savedCenterX, savedCenterY)
        val tParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (savedCenterX - touchRadius).roundToInt()
            y = (savedCenterY - touchRadius).roundToInt()
        }
        touchParams = tParams

        touch.onBallMoved = { cx, cy ->
            savedCenterX = cx
            savedCenterY = cy
            moveTouchWindow(cx, cy)
            draw.invalidate()
        }
        touch.onPositionChanged = { cx, cy ->
            savedCenterX = cx
            savedCenterY = cy
            OverlayPositionPrefs.save(applicationContext, cx, cy)
        }
        touch.onMoveStateChanged = { _ ->
            resizeTouchWindow(touch.getCurrentVisualRadius())
        }
        touch.onGoHome                 = { goHome() }
        touch.onLaunchApp              = { pkg -> launchApp(pkg) }
        touch.onLaunchPinnedApp        = { pkg -> launchApp(pkg) }
        touch.onHapticFeedback         = { performHaptic() }
        touch.onDrawInvalidate         = { draw.invalidate() }
        touch.onDoubleTapTemporaryHide = { temporarilyHideOverlay() }

        draw.touchView = touch
        touchView      = touch
        windowManager.addView(touch, tParams)

        applyBallVisibility()
    }

    private fun removeOverlayViews() {
        drawView?.let {
            it.touchView = null
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        drawView = null

        touchView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        touchView   = null
        touchParams = null
    }

    private fun moveTouchWindow(cx: Float, cy: Float) {
        val params = touchParams ?: return
        val half   = touchWindowSize / 2f
        params.x   = (cx - half).roundToInt()
        params.y   = (cy - half).roundToInt()
        try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
    }

    private fun resizeTouchWindow(radius: Float) {
        val params  = touchParams ?: return
        val newSize = (radius * 2f).roundToInt()
        if (touchWindowSize == newSize) return
        touchWindowSize = newSize
        params.width  = newSize
        params.height = newSize
        params.x = (savedCenterX - radius).roundToInt()
        params.y = (savedCenterY - radius).roundToInt()
        try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
    }

    // ── 非表示理由管理 ─────────────────────────────────────────────

    /**
     * 前面アプリと hiddenPackages を照合して hiddenApp を更新し、
     * captureMode の変化を applyCaptureMode() 経由で反映する。
     */
    private fun applyBallVisibility() {
        val currentPkg   = LauncherRepository.currentForegroundPackage
        val hiddenPkgs   = LauncherRepository.config.hiddenPackages
        val newHiddenApp = currentPkg != null && currentPkg in hiddenPkgs

        hiddenReasons = hiddenReasons.copy(hiddenApp = newHiddenApp)
        applyCaptureMode(LauncherRepository.config.captureMode)

        logVisibilityState(currentPkg)
        updateOverlayVisibility()
    }

    /** ダブルタップによる一時非表示。設定秒数後に temporaryHide = false で再評価する。 */
    private fun temporarilyHideOverlay() {
        temporaryHideJob?.cancel()
        hiddenReasons = hiddenReasons.copy(temporaryHide = true)
        updateOverlayVisibility()
        temporaryHideJob = serviceScope.launch {
            delay(LauncherRepository.config.temporaryHideSeconds * 1000L)
            hiddenReasons = hiddenReasons.copy(temporaryHide = false)
            updateOverlayVisibility()
        }
    }

    /**
     * 撮影モードの ON/OFF を処理する。
     * ON 時は 3 秒後に hiddenReasons.captureMode = true → applyHide。
     * OFF 時は即座に captureMode = false → applyShow（他の隠し理由がなければ）。
     */
    private fun applyCaptureMode(wantCapture: Boolean) {
        if (!wantCapture) {
            captureHideJob?.cancel()
            captureHideJob = null
            if (hiddenReasons.captureMode) {
                hiddenReasons = hiddenReasons.copy(captureMode = false)
                updateOverlayVisibility()
                updateNotification()
            }
            return
        }
        // すでに有効 or ディレイ中なら二重起動しない
        if (hiddenReasons.captureMode || captureHideJob?.isActive == true) return
        captureHideJob = serviceScope.launch {
            delay(CAPTURE_HIDE_DELAY_MS)
            captureHideJob = null
            hiddenReasons = hiddenReasons.copy(captureMode = true)
            updateOverlayVisibility()
            updateNotification()
        }
    }

    /** hiddenReasons.any に基づいて show/hide を適用する唯一の関数。 */
    private fun updateOverlayVisibility() {
        if (hiddenReasons.any) applyHide() else applyShow()
    }

    /**
     * フェードアウトで非表示にする。
     * FLAG_NOT_TOUCHABLE を即座に追加してから Animator を開始する。
     * visibilityGeneration で古い endAction を無効化する。
     */
    private fun applyHide() {
        val gen = ++visibilityGeneration

        setTouchable(false)

        val dv = drawView ?: return
        val tv = touchView ?: return

        tv.cancelGesture()  // PINNED_MENU 中に非表示になった場合の状態リセット
        tv.isEnabled = false

        dv.animate().cancel()
        tv.animate().cancel()

        dv.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction {
                if (gen == visibilityGeneration) {
                    dv.visibility = View.INVISIBLE
                    tv.visibility = View.INVISIBLE
                }
            }
        tv.animate().alpha(0f).setDuration(FADE_DURATION_MS)
    }

    /**
     * フェードインで表示する。
     * FLAG_NOT_TOUCHABLE を除去してから Animator を開始する。
     * visibilityGeneration で古い endAction を無効化する。
     */
    private fun applyShow() {
        ++visibilityGeneration

        val dv = drawView ?: return
        val tv = touchView ?: return

        dv.animate().cancel()
        tv.animate().cancel()

        setTouchable(true)

        if (dv.visibility != View.VISIBLE) { dv.alpha = 0f; dv.visibility = View.VISIBLE }
        if (tv.visibility != View.VISIBLE) { tv.alpha = 0f; tv.visibility = View.VISIBLE }

        tv.isEnabled = true

        dv.animate().alpha(1f).setDuration(FADE_DURATION_MS)
        tv.animate().alpha(1f).setDuration(FADE_DURATION_MS)
    }

    /**
     * touch Window の FLAG_NOT_TOUCHABLE を設定 / 解除する。
     * 変化がない場合は updateViewLayout を呼ばない。
     */
    private fun setTouchable(touchable: Boolean) {
        val params = touchParams ?: return
        val flag   = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val newFlags = if (touchable) params.flags and flag.inv() else params.flags or flag
        if (params.flags == newFlags) return
        params.flags = newFlags
        try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
    }

    /** configFlow 変化時。hiddenPackages・captureMode・buttonRadiusPx 変化をすべて処理する。 */
    private fun onConfigUpdated() {
        touchView?.let { resizeTouchWindow(it.getCurrentVisualRadius()) }
        applyBallVisibility()
    }

    // ── PullyVisibility ログ ──────────────────────────────────────

    private fun logForegroundDetected(
        before: String?,
        after: String,
        eventTimestampMs: Long,
        isInitial: Boolean
    ) {
        if (0 == (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) return
        val kind = if (isInitial) "initial(60s)" else "event"
        Log.d("PullyVisibility",
            "foreground[$kind] before=$before after=$after " +
            "home=${resolveHomeLauncherPackage()} eventTs=$eventTimestampMs")
    }

    private fun logVisibilityState(pkg: String?) {
        if (0 == (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) return
        val reasons      = hiddenReasons
        val dv           = drawView
        val tv           = touchView
        val tp           = touchParams
        val drawVis      = visibilityStr(dv?.visibility)
        val drawAlpha    = dv?.alpha ?: 0f
        val touchVis     = visibilityStr(tv?.visibility)
        val touchAlpha   = tv?.alpha ?: 0f
        val notTouchable = (tp?.flags?.and(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) ?: 0) != 0
        val tx = tp?.x ?: 0
        val ty = tp?.y ?: 0
        val tw = tp?.width ?: 0
        val th = tp?.height ?: 0

        val pkgChanged     = pkg != lastLogPkg
        val reasonsChanged = reasons != lastLogReasons
        val visChanged     = "$drawVis:$touchVis" != lastLogVis
        val touchChanged   = notTouchable != lastLogTouchNotTouchable
        if (!pkgChanged && !reasonsChanged && !visChanged && !touchChanged) return

        lastLogPkg               = pkg
        lastLogReasons           = reasons
        lastLogVis               = "$drawVis:$touchVis"
        lastLogTouchNotTouchable = notTouchable

        Log.d("PullyVisibility",
            "pkg=$pkg home=${resolveHomeLauncherPackage()} " +
            "hiddenApp=${reasons.hiddenApp} tmpHide=${reasons.temporaryHide} capture=${reasons.captureMode} " +
            "effectiveHidden=${reasons.any} " +
            "drawVis=$drawVis drawAlpha=${"%.2f".format(drawAlpha)} " +
            "touchVis=$touchVis touchAlpha=${"%.2f".format(touchAlpha)} " +
            "notTouchable=$notTouchable x=$tx y=$ty w=$tw h=$th")
    }

    private fun visibilityStr(vis: Int?) = when (vis) {
        View.VISIBLE   -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        null           -> "null"
        else           -> "unknown($vis)"
    }

    @Suppress("DEPRECATION")
    private fun resolveHomeLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        return packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
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

    private fun performHaptic() {
        val vib = vibrator ?: return
        vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
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
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @SuppressLint("NotificationPermission")
    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isCaptureMode = hiddenReasons.captureMode
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                if (isCaptureMode) getString(R.string.notif_capture_mode_title)
                else getString(R.string.app_name)
            )
            .setContentText(
                if (isCaptureMode) getString(R.string.notif_capture_mode_text)
                else getString(R.string.notif_running_text)
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (isCaptureMode) {
            val disableIntent = PendingIntent.getService(
                this, 1,
                Intent(this, OverlayService::class.java).apply {
                    action = ACTION_DISABLE_CAPTURE_MODE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_dialog_info),
                    getString(R.string.notif_show_pully),
                    disableIntent
                ).build()
            )
        }
        return builder.build()
    }
}
