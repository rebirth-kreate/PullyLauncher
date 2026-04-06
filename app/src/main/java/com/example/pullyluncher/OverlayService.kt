package com.example.pullyluncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import com.example.pullyluncher.data.UiConfigPrefs
import com.example.pullyluncher.model.ColorPresets
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * フローティングボタンと展開 UI を他アプリ上に表示するサービス。
 *
 * ── ジェスチャー仕様 ──────────────────────────────────────────────
 *   短タップ              → ホーム画面へ戻る
 *   500ms 静止長押し       → ドラッグ移動モード（拡大 + 半透明フィードバック）
 *   押しっぱなし + ドラッグ → 展開 UI を表示（OverlayExpandView）
 *
 * ── 座標系の統一方針 ──────────────────────────────────────────────
 *   すべての座標を「オーバーレイ窓空間」に統一する。
 *
 *   ・ボール中心 = layoutParams.x/y（既にオーバーレイ座標系）+ buttonSizePx/2
 *     getLocationOnScreen() は使わない（スクリーン座標 = statusBarHeight ずれが発生）
 *
 *   ・展開 View に FLAG_LAYOUT_IN_SCREEN を付与しない
 *     → canvas(0,0) = オーバーレイ窓の左上 = layoutParams 座標と同じ系
 *
 *   ・updateDrag には rawY - statusBarHeightPx を渡す
 *     rawX/rawY はスクリーン座標なので Y だけ補正してオーバーレイ座標に変換する
 *     rawX はステータスバーの影響を受けないのでそのまま渡す
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayButton: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // 展開 UI
    private var expandView: OverlayExpandView? = null

    private val mainHandler       = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private var isDragMode  = false
    private var isExpanding = false

    private var startParamX      = 0
    private var startParamY      = 0
    private var touchRawX        = 0f
    private var touchRawY        = 0f
    private var buttonSizePx     = 0
    private var statusBarHeightPx = 0   // onCreate で一度だけ計算してキャッシュ

    // ボール中心 (オーバーレイ座標系、ACTION_DOWN で確定)
    private var ballCenterX = 0f
    private var ballCenterY = 0f

    companion object {
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID     = "pully_overlay_channel"
        private const val NOTIF_ID       = 1001
        private const val LONG_PRESS_MS  = 500L
        private const val MOVE_CANCEL_PX = 12
    }

    // ── ライフサイクル ────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // アプリ一覧が未ロードなら非同期でロードを開始する（展開操作までに完了を狙う）
        LauncherRepository.loadAppsIfNeeded(this)
        // START_STICKY: システムにサービスが強制終了された場合でも自動再起動する
        // オーバーレイが失われていれば再追加（再起動時の防御）
        if (overlayButton == null) {
            addFloatingButton()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // ステータスバー高さをキャッシュ（rawY → オーバーレイY の変換に使う）
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeightPx = if (id > 0) resources.getDimensionPixelSize(id) else 0

        // config 変更時に待機ボールの色を即時更新するコールバックを登録
        LauncherRepository.onConfigChanged = {
            mainHandler.post { overlayButton?.invalidate() }
        }

        // Activity 起動前（再起動時等）でも nodeCount / colorPreset を正しく反映するため
        // 永続化済み config を適用する。Activity 経由の場合も prefs は最新値なので安全。
        LauncherRepository.config = UiConfigPrefs.load(this)

        // アプリ一覧を事前ロード（onStartCommand より早く呼ばれるケースへの保険）
        LauncherRepository.loadAppsIfNeeded(this)
        startForegroundWithNotification()
        addFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        LauncherRepository.onConfigChanged = null
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        hideExpandView()
        overlayButton?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayButton = null
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

    // ── フローティングボタン ──────────────────────────────────────

    private fun addFloatingButton() {
        val density  = resources.displayMetrics.density
        buttonSizePx = (64 * density).toInt()

        val button = BallView(this, buttonSizePx)

        layoutParams = WindowManager.LayoutParams(
            buttonSizePx, buttonSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (200 * density).toInt()
        }

        button.setOnTouchListener(buildTouchListener(button))
        overlayButton = button
        windowManager.addView(button, layoutParams)
    }

    // ── 展開 View 管理 ────────────────────────────────────────────

    /**
     * 展開 View を生成して WindowManager に追加。
     *
     * FLAG_LAYOUT_IN_SCREEN は付与しない。
     * → canvas(0,0) がオーバーレイ窓空間の左上になり、
     *   layoutParams.x/y と同じ座標系に揃う。
     *
     * FLAG_NOT_TOUCHABLE により、タッチは BallView 側のウィンドウが受け取る。
     */
    private fun showExpandView() {
        if (expandView != null) return
        val view   = OverlayExpandView(
            this, ballCenterX, ballCenterY, buttonSizePx / 2f,
            LauncherRepository.config,
            LauncherRepository.appSlots
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        expandView = view
        windowManager.addView(view, params)
    }

    private fun hideExpandView() {
        expandView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        expandView = null
    }

    // ── タッチハンドラ ────────────────────────────────────────────
    //
    //   DOWN  → 500ms タイマー開始
    //           layoutParams.x/y からボール中心をオーバーレイ座標で計算
    //
    //   MOVE（isDragMode）    → ボタン座標を更新
    //   MOVE（isExpanding）   → expandView.updateDrag(rawX, rawY - statusBar) を relay
    //   MOVE（それ以外 & 閾値超え）→ タイマーキャンセル、展開モード開始
    //
    //   UP    → タイマーキャンセル
    //          + isExpanding  → 展開 View を非表示
    //          + isDragMode   → 外観復元
    //          + それ以外      → ホームへ（短タップ）
    //
    private fun buildTouchListener(view: View): View.OnTouchListener =
        View.OnTouchListener { _, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    startParamX = layoutParams.x
                    startParamY = layoutParams.y
                    touchRawX   = event.rawX
                    touchRawY   = event.rawY
                    isDragMode  = false
                    isExpanding = false

                    // 展開直前に使用履歴順を毎回更新する（ステールチェックなし）
                    LauncherRepository.refreshHistoryAsync(applicationContext)

                    // ボール中心をオーバーレイ座標系で計算。
                    // layoutParams.x/y は TYPE_APPLICATION_OVERLAY ウィンドウの配置座標 =
                    // オーバーレイ窓空間の座標（ステータスバー分を含まない）なのでそのまま使える。
                    ballCenterX = layoutParams.x + buttonSizePx / 2f
                    ballCenterY = layoutParams.y + buttonSizePx / 2f

                    val runnable = Runnable {
                        isDragMode = true
                        view.animate()
                            .scaleX(1.18f).scaleY(1.18f)
                            .alpha(0.72f)
                            .setDuration(120)
                            .start()
                    }
                    longPressRunnable = runnable
                    mainHandler.postDelayed(runnable, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchRawX).toInt()
                    val dy = (event.rawY - touchRawY).toInt()

                    when {
                        isDragMode -> {
                            layoutParams.x = startParamX + dx
                            layoutParams.y = startParamY + dy
                            windowManager.updateViewLayout(view, layoutParams)
                        }
                        isExpanding -> {
                            // rawX はスクリーン X = オーバーレイ X（水平方向は同じ）
                            // rawY はスクリーン Y なので statusBarHeight を引いてオーバーレイ Y に変換
                            expandView?.updateDrag(event.rawX, event.rawY - statusBarHeightPx)
                        }
                        dx * dx + dy * dy > MOVE_CANCEL_PX * MOVE_CANCEL_PX -> {
                            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            longPressRunnable = null
                            isExpanding = true
                            showExpandView()
                            expandView?.startDrag()
                            expandView?.updateDrag(event.rawX, event.rawY - statusBarHeightPx)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null

                    when {
                        isExpanding -> {
                            val selIdx    = expandView?.selectedIndex ?: -1
                            val cancelled = expandView?.isCancelled   ?: true
                            hideExpandView()
                            // 選択済みかつキャンセルでなければ対応アプリを起動
                            if (!cancelled && selIdx >= 0) {
                                val app = LauncherRepository.appSlots.getOrNull(selIdx)?.pinnedApp
                                if (app != null) {
                                    packageManager.getLaunchIntentForPackage(app.packageName)
                                        ?.let { intent ->
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(intent)
                                        }
                                }
                            }
                        }
                        isDragMode -> {
                            view.animate()
                                .scaleX(1f).scaleY(1f)
                                .alpha(1f)
                                .setDuration(120)
                                .start()
                        }
                        else -> {
                            // シングルタップ → ホーム画面へ
                            // TODO: ダブルタップ検出を追加して ARK を起動する場合はここを拡張する
                            startActivity(
                                Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }

                    isDragMode  = false
                    isExpanding = false
                    true
                }

                else -> false
            }
        }

    // ── BallView ─────────────────────────────────────────────────

    private inner class BallView(
        context: android.content.Context,
        private val sizePx: Int
    ) : View(context) {

        private val fillPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = sizePx / 2f
            val cy = sizePx / 2f
            val r  = sizePx / 2f

            // 毎描画で config から現在のプリセット色を取得する（設定変更が即座に反映される）
            fillPaint.color = ColorPresets.get(LauncherRepository.config.colorPreset).buttonColor

            canvas.drawCircle(cx, cy, r, fillPaint)

            val hlRadius = r * 0.62f
            val hlCy     = cy - r * 0.22f
            highlightPaint.shader = RadialGradient(
                cx, hlCy, hlRadius,
                intArrayOf(0x33FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, hlCy, hlRadius, highlightPaint)
        }
    }
}
