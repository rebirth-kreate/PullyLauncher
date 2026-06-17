package com.example.pullyluncher

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
 *
 * ── 前面アプリ検知 ────────────────────────────────────────────────────
 *   UsageStatsManager.queryEvents() を 750ms ポーリングで使用。
 *   対象イベント: MOVE_TO_FOREGROUND のみ（value=1、ACTIVITY_RESUMED と同値）。
 *   value=19 の FOREGROUND_SERVICE_START は Activity の前面移動ではないため除外。
 *   カーソル方式: lastFgEventTimestampMs より新しいイベントだけを処理する。
 *   権限なし・画面消灯中はスキップ。イベントなしは現在のパッケージを維持。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private var touchView: OverlayTouchView? = null
    private var drawView: OverlayExpandView? = null

    /** touch Window の LayoutParams。移動・リサイズ・フラグ変更時に updateViewLayout で使う。 */
    private var touchParams: WindowManager.LayoutParams? = null
    /** draw Window の LayoutParams。FLAG_SECURE 変更時に updateViewLayout で使う。 */
    private var drawParams: WindowManager.LayoutParams? = null
    /** touch Window のサイズ（正方形の一辺）。 */
    private var touchWindowSize = 0

    private var savedCenterX = 0f
    private var savedCenterY = 0f

    @Suppress("DEPRECATION")
    private var vibrator: Vibrator? = null

    private var isTemporarilyHidden = false
    private var temporaryHideJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // ── ポーリング状態 ────────────────────────────────────────────
    /**
     * 最後に処理した MOVE_TO_FOREGROUND イベントのタイムスタンプ（ms）。
     * 0L = 未取得（初回・権限失効後の再取得）→ INITIAL_LOOKBACK_MS を使う。
     * これより新しいイベントだけを queryForegroundEvent が返す（カーソル方式）。
     */
    private var lastFgEventTimestampMs: Long = 0L
    /** 最後に確定した前面パッケージ名。重複処理スキップに使う。 */
    private var lastPolledPkg: String? = null
    /** 前回確認した Usage Access 権限状態。 */
    private var lastPolledHasPermission: Boolean? = null

    // ── ログ差分検出 ─────────────────────────────────────────────
    private var lastLogPkg: String? = null
    private var lastLogIsHidden: Boolean? = null
    private var lastLogHasPermission: Boolean? = null

    companion object {
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID               = "pully_overlay_channel"
        private const val NOTIF_ID                 = 1001
        private const val FADE_DURATION_MS         = 150L
        /** UsageStats 前面アプリポーリング間隔（ミリ秒）。 */
        private const val FOREGROUND_POLL_INTERVAL_MS = 750L
        /** サービス起動時・権限回復時の初期ルックバック（60秒）。 */
        private const val INITIAL_LOOKBACK_MS = 60_000L
        /** 通常ポーリング時のルックバック上限（5秒）。大半は lastFgEventTimestampMs で clamp される。 */
        private const val POLL_LOOKBACK_MS    = 5_000L
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

        serviceScope.launch {
            LauncherRepository.configFlow.collect { onConfigUpdated() }
        }

        LauncherRepository.onIconsLoaded = { drawView?.invalidate() }

        // UsageStats 前面アプリポーリング（初回即時 + 750ms 間隔）
        serviceScope.launch {
            // 初回: 遅延なし・60秒ルックバックで起動直後の前面アプリを取得
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
     * ・画面消灯中: スキップ（アクティブな前面アプリはなし）。
     * ・権限なし: 前面パッケージをクリアして Pully を表示状態に戻す。
     */
    private fun pollForegroundPackage() {
        if (!powerManager.isInteractive) return

        val hasPermission = UsageHistoryRepository.hasPermission(applicationContext)
        val permChanged   = hasPermission != lastPolledHasPermission
        lastPolledHasPermission = hasPermission

        if (!hasPermission) {
            if (permChanged) {
                // 権限が失われた — 前面パッケージをクリアし、次回権限回復時に初期ルックバックを使わせる
                LauncherRepository.currentForegroundPackage = null
                lastPolledPkg          = null
                lastFgEventTimestampMs = 0L
                applyBallVisibility()
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
            // タイムスタンプを常に更新して同一イベントの再処理を防ぐ
            lastFgEventTimestampMs = ts
            if (pkg != lastPolledPkg || permChanged) {
                val before    = LauncherRepository.currentForegroundPackage
                lastPolledPkg = pkg
                LauncherRepository.currentForegroundPackage = pkg
                logForegroundDetected(before, pkg, ts, isInitial)
                applyBallVisibility()
            }
        } else {
            // 新規フォアグラウンドイベントなし → 現在のパッケージを維持
            if (permChanged) applyBallVisibility()
        }
    }

    // ── 2ウィンドウ管理 ──────────────────────────────────────────

    private fun addOverlayViews() {
        val cfg        = LauncherRepository.config
        val secureFlag = if (cfg.secureOverlay) WindowManager.LayoutParams.FLAG_SECURE else 0

        // ── draw Window（全画面・FLAG_NOT_TOUCHABLE）──────────────────
        val draw = OverlayExpandView(this)
        val dParams = WindowManager.LayoutParams(
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
        drawView   = draw
        drawParams = dParams
        windowManager.addView(draw, dParams)

        // ── touch Window（ボールサイズ・タッチ可能）──────────────────
        val touchRadius = cfg.buttonRadiusPx
        val size        = (touchRadius * 2f).roundToInt()
        touchWindowSize = size

        val touch   = OverlayTouchView(this, savedCenterX, savedCenterY)
        val tParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    secureFlag,
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
        drawView   = null
        drawParams = null

        touchView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        touchView   = null
        touchParams = null
    }

    /** touch Window の位置をボール中心に合わせる。 */
    private fun moveTouchWindow(cx: Float, cy: Float) {
        val params = touchParams ?: return
        val half   = touchWindowSize / 2f
        params.x   = (cx - half).roundToInt()
        params.y   = (cy - half).roundToInt()
        try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
    }

    /**
     * touch Window のサイズを視覚ボール半径に合わせてリサイズする。
     * MOVING 開始/終了時に呼ばれる。
     */
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

    // ── ボール可視制御 ─────────────────────────────────────────────

    /**
     * 現在の前面アプリと hiddenPackages を照合して表示/非表示を決定する。
     * isTemporarilyHidden が true の場合は常に非表示を維持する。
     */
    private fun applyBallVisibility() {
        if (isTemporarilyHidden) {
            hideOverlay()
            return
        }

        val currentPkg  = LauncherRepository.currentForegroundPackage
        val hiddenPkgs  = LauncherRepository.config.hiddenPackages
        val shouldHide  = currentPkg != null && currentPkg in hiddenPkgs
        val hasAccess   = lastPolledHasPermission ?: false

        // PullyVisibility ログ — 状態が変化したときだけ出力
        val pkgChanged    = currentPkg != lastLogPkg
        val hiddenChanged = shouldHide != lastLogIsHidden
        val permChanged   = hasAccess  != lastLogHasPermission
        if (pkgChanged || hiddenChanged || permChanged) {
            val before           = lastLogPkg
            lastLogPkg           = currentPkg
            lastLogIsHidden      = shouldHide
            lastLogHasPermission = hasAccess
            logVisibilityState(before, currentPkg, hasAccess, hiddenPkgs.size, shouldHide)
        }

        if (shouldHide) hideOverlay() else showOverlay()
    }

    /**
     * ダブルタップによる一時非表示。
     * 設定秒数後に isTemporarilyHidden = false にして再判定する。
     */
    private fun temporarilyHideOverlay() {
        temporaryHideJob?.cancel()
        isTemporarilyHidden = true
        hideOverlay()
        temporaryHideJob = serviceScope.launch {
            delay(LauncherRepository.config.temporaryHideSeconds * 1000L)
            isTemporarilyHidden = false
            applyBallVisibility()
        }
    }

    /**
     * オーバーレイをフェードアウトで非表示にする。
     * touch Window に FLAG_NOT_TOUCHABLE を追加して透明なタッチ領域が残らないようにする。
     */
    private fun hideOverlay() {
        if (drawView?.visibility == View.INVISIBLE) return
        if ((drawView?.alpha ?: 1f) <= 0f) return

        // タッチ領域を完全に無効化（背面アプリのタッチを妨げない）
        touchParams?.let { params ->
            val newFlags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (params.flags != newFlags) {
                params.flags = newFlags
                try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
            }
        }

        drawView?.animate()
            ?.alpha(0f)
            ?.setDuration(FADE_DURATION_MS)
            ?.withEndAction {
                if ((drawView?.alpha ?: 1f) < 0.01f) drawView?.visibility = View.INVISIBLE
            }
        touchView?.animate()
            ?.alpha(0f)
            ?.setDuration(FADE_DURATION_MS)
            ?.withEndAction {
                if ((touchView?.alpha ?: 1f) < 0.01f) touchView?.visibility = View.INVISIBLE
            }
    }

    /**
     * オーバーレイをフェードインで表示する。
     * touch Window から FLAG_NOT_TOUCHABLE を除去してタッチを再有効化する。
     */
    private fun showOverlay() {
        if (drawView?.visibility == View.VISIBLE && (drawView?.alpha ?: 0f) >= 1f) return

        if (drawView?.visibility != View.VISIBLE) {
            drawView?.alpha = 0f
            drawView?.visibility = View.VISIBLE
        }
        if (touchView?.visibility != View.VISIBLE) {
            touchView?.alpha = 0f
            touchView?.visibility = View.VISIBLE
        }

        // FLAG_NOT_TOUCHABLE を除去してタッチ受付を復元
        touchParams?.let { params ->
            val newFlags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (params.flags != newFlags) {
                params.flags = newFlags
                try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
            }
        }

        drawView?.animate()?.alpha(1f)?.setDuration(FADE_DURATION_MS)
        touchView?.animate()?.alpha(1f)?.setDuration(FADE_DURATION_MS)
    }

    /**
     * configFlow 変化時。buttonRadiusPx 変化 / FLAG_SECURE 切替 / hiddenPackages 変化をすべて処理する。
     */
    private fun onConfigUpdated() {
        touchView?.let { resizeTouchWindow(it.getCurrentVisualRadius()) }
        applySecureOverlay()
        applyBallVisibility()
    }

    // ── FLAG_SECURE 適用 ─────────────────────────────────────────

    /**
     * secureOverlay 設定に従い drawView / touchView 両 Window の FLAG_SECURE を即時切替する。
     * Service 再起動不要。updateViewLayout で既存 Window を更新する。
     */
    private fun applySecureOverlay() {
        val secure = LauncherRepository.config.secureOverlay
        val flag   = WindowManager.LayoutParams.FLAG_SECURE

        drawParams?.let { params ->
            val newFlags = if (secure) params.flags or flag else params.flags and flag.inv()
            if (params.flags == newFlags) return@let
            params.flags = newFlags
            try { drawView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
        }
        touchParams?.let { params ->
            val newFlags = if (secure) params.flags or flag else params.flags and flag.inv()
            if (params.flags == newFlags) return@let
            params.flags = newFlags
            try { touchView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
        }
    }

    // ── PullyVisibility ログ ──────────────────────────────────────

    /**
     * 前面アプリが変化したときにイベント取得結果をログ出力する。
     * デバッグビルドのみ出力。同一パッケージへの更新時は呼ばれない。
     */
    private fun logForegroundDetected(
        before: String?,
        after: String,
        eventTimestampMs: Long,
        isInitial: Boolean
    ) {
        if (0 == (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) return
        val kind = if (isInitial) "initial(60s)" else "event"
        Log.d("PullyVisibility",
            "foreground[$kind] before=$before after=$after eventTs=$eventTimestampMs")
    }

    private fun logVisibilityState(
        before: String?,
        pkg: String?,
        usageAccess: Boolean,
        hiddenCount: Int,
        isHidden: Boolean
    ) {
        if (0 == (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) return
        val drawVis  = visibilityStr(drawView?.visibility)
        val touchVis = visibilityStr(touchView?.visibility)
        val result   = if (isHidden) "HIDE" else "SHOW"
        Log.d("PullyVisibility",
            "usageAccess=$usageAccess " +
            "foregroundBefore=$before foregroundAfter=$pkg " +
            "hiddenCount=$hiddenCount isHidden=$isHidden " +
            "drawVisibility=$drawVis touchVisibility=$touchVis result=$result")
    }

    private fun visibilityStr(vis: Int?) = when (vis) {
        View.VISIBLE   -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        null           -> "null"
        else           -> "unknown($vis)"
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
