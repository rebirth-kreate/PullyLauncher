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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.pullyluncher.data.OverlayPositionPrefs
import com.example.pullyluncher.data.UiConfigPrefs
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

    /**
     * ダブルタップによる一時非表示の状態フラグ。
     * true の間は applyBallVisibility が必ず hideOverlay を呼ぶ。
     * temporaryHideJob の完了時に false に戻り、applyBallVisibility で再判定する。
     */
    private var isTemporarilyHidden = false
    private var temporaryHideJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    companion object {
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID      = "pully_overlay_channel"
        private const val NOTIF_ID        = 1001
        /** フェードアニメーションの長さ（ミリ秒）。調整可能。 */
        private const val FADE_DURATION_MS = 150L
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

        // 初期ボール位置: 左上から 16dp・200dp の位置をデフォルトとし、
        // 保存済み位置があればそちらを優先して復元する。
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

        // アクセシビリティサービスのイベント漏れを補完するポーリング（300ms 間隔）
        // hiddenPackages によるオーバーレイ非表示が確実に反映されるようにする
        serviceScope.launch {
            while (true) {
                delay(300L)
                applyBallVisibility()
            }
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
        val size = (touchRadius * 2f).roundToInt()
        touchWindowSize = size

        val touch = OverlayTouchView(this, savedCenterX, savedCenterY)
        val tParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL: ボール外のタッチを後ろのウィンドウへ透過させる。
            // これがないと、円形ヒットテストで return false しても
            // 他アプリのポップアップ等がタッチを受け取れない。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (savedCenterX - touchRadius).roundToInt()
            y = (savedCenterY - touchRadius).roundToInt()
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
            // 指を離したタイミングで位置を永続化する（移動中は保存しない）
            OverlayPositionPrefs.save(applicationContext, cx, cy)
        }
        touch.onMoveStateChanged = { _ ->
            // touchState はコールバック発火前に更新済みなので
            // getCurrentVisualRadius() が常に遷移後の正しい半径を返す
            resizeTouchWindow(touch.getCurrentVisualRadius())
        }
        touch.onGoHome                 = { goHome() }
        touch.onLaunchApp              = { pkg -> launchApp(pkg) }
        touch.onHapticFeedback         = { performHaptic() }
        touch.onDrawInvalidate         = { draw.invalidate() }
        touch.onDoubleTapTemporaryHide = { temporarilyHideOverlay() }

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
        params.x = (cx - half).roundToInt()
        params.y = (cy - half).roundToInt()
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
        val newSize = (radius * 2f).roundToInt()
        if (touchWindowSize == newSize) return
        touchWindowSize = newSize
        params.width  = newSize
        params.height = newSize
        params.x = (savedCenterX - radius).roundToInt()
        params.y = (savedCenterY - radius).roundToInt()
        try {
            touchView?.let { windowManager.updateViewLayout(it, params) }
        } catch (_: Exception) {}
    }

    // ── ボール可視制御 ─────────────────────────────────────────────

    /**
     * 現在の前面アプリだけで表示/非表示を毎回決定する。
     * 状態フラグを持たず、常に上書き設定することで「前の状態を引きずる」バグを防ぐ。
     *
     * currentForegroundPackage は ForegroundAppService が即時更新する。
     * refreshHistoryAsync による遅延上書きは LauncherRepository 側で禁止済み。
     */
    private fun applyBallVisibility() {
        // ① 一時非表示中は hiddenPackages 判定より優先して非表示を維持する。
        //    タイマー完了後に isTemporarilyHidden = false にしてから再度ここを呼ぶ。
        if (isTemporarilyHidden) {
            hideOverlay()
            return
        }

        // ② hiddenPackages 判定
        val currentPkg  = LauncherRepository.currentForegroundPackage
        val hiddenPkgs  = LauncherRepository.config.hiddenPackages
        val shouldHide  = currentPkg != null && currentPkg in hiddenPkgs

        Log.d("Pully", "applyBallVisibility: currentPkg=$currentPkg shouldHide=$shouldHide")

        if (shouldHide) hideOverlay() else showOverlay()
    }

    /**
     * ダブルタップによる一時非表示。
     *
     * ・設定秒数（temporaryHideSeconds）後に isTemporarilyHidden = false にして
     *   applyBallVisibility() を呼ぶことで再表示可否を判定する。
     * ・hiddenPackages 対象アプリを開いていれば applyBallVisibility が hideOverlay を選ぶため
     *   そのまま非表示が継続する。
     * ・連続ダブルタップ時は既存タイマーをキャンセルして再スタートする。
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
     * すでに非表示（INVISIBLE）なら何もしない。
     *
     * withEndAction 内でアルファ値を再確認することで、
     * アニメーション途中に showOverlay() が呼ばれた場合でも
     * 誤って INVISIBLE にセットしてしまう競合を防ぐ。
     */
    private fun hideOverlay() {
        if (drawView?.visibility == View.INVISIBLE) return
        // alpha がすでに 0 なら withEndAction の完了待ち中 → 二重起動しない
        if ((drawView?.alpha ?: 1f) <= 0f) return

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
     * すでに完全表示（VISIBLE かつ alpha == 1f）なら何もしない。
     *
     * hideOverlay() のアニメーション途中に呼ばれた場合:
     *   ・VISIBLE のままなので alpha だけ 0f にリセットせず現在値から継続
     *   ・animate().alpha(1f) が逆方向アニメーションを上書き開始する
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
        drawView?.animate()?.alpha(1f)?.setDuration(FADE_DURATION_MS)
        touchView?.animate()?.alpha(1f)?.setDuration(FADE_DURATION_MS)
    }

    /**
     * configFlow 変化時。
     * ・buttonRadiusPx が変わった場合に touch Window を新サイズへ即時同期する。
     *   （resizeTouchWindow は同サイズなら何もしないので冪等）
     * ・View 自身が configFlow を collect して invalidate() するため、
     *   描画の再指示はサービス側では不要。
     * ・hiddenPackages の可視制御のみ applyBallVisibility に委ねる。
     */
    private fun onConfigUpdated() {
        // buttonRadiusPx ではなく、現在の状態（IDLE/MOVING）込みの正確な半径を使う。
        // MOVING 中にサイズ設定を変更した場合も正しくリサイズされる。
        touchView?.let { resizeTouchWindow(it.getCurrentVisualRadius()) }
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
