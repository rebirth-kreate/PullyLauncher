package com.example.pullyluncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

private const val LONG_PRESS_MS   = 500L
private const val MOVE_CANCEL_PX  = 28
/**
 * ダブルタップと判定する最大間隔（ミリ秒）。
 * 1回目のタップ離し → 2回目のタップ離しまでの時間がこれ以内ならダブルタップ。
 * シングルタップの goHome もこの時間だけ遅延して発火する。
 * 調整可能: 250〜350ms。
 */
private const val DOUBLE_TAP_MS   = 300L
/**
 * 円形ヒットテストのマージン（px）。
 * 視覚半径からこの値だけ縮めることで、Window 端の toInt() 誤差と
 * 描画アンチエイリアス領域への誤タップを防ぐ。
 * 調整可能: 2f〜4f。大きいほど厳しくなる。
 */
private const val HIT_MARGIN = 2f

/**
 * ドラッグ方向スムージング係数（ボール内のみ有効）。
 *
 *   axis はボール内（dist < buttonRadiusPx × 1.2）でのみこの係数で更新される。
 *   ボール外へ出た瞬間に axisLocked = true となり、以後 axis は一切更新されない。
 *
 * ── チューニングガイド ──────────────────────────────────────────────────
 *   大きい（0.25〜0.35）→ 素早く指の方向へ収束する
 *   小さい（0.10〜0.15）→ ゆっくり収束するが残像感が強い
 * 推奨: 0.18
 */
private const val AXIS_SMOOTH_FACTOR = 0.18f

/**
 * タッチ専用の小さい View。
 *
 * ── 役割 ──────────────────────────────────────────────────────────────
 *   ボールサイズの Window に配置され、タッチイベントのみを処理する。
 *   描画は行わない（完全透明）。
 *   状態変化は onDrawInvalidate / onBallMoved コールバックで Service に通知し、
 *   Service が描画 Window（OverlayExpandView）を更新する。
 *
 * ── 座標系 ─────────────────────────────────────────────────────────────
 *   すべての計算に rawX/rawY（スクリーン座標）を使う。
 *   Window の位置はあくまでタッチ受付エリアを示すだけであり、
 *   座標計算には影響しない。
 */
class OverlayTouchView(
    context: Context,
    initCenterX: Float,
    initCenterY: Float,
) : View(context) {

    // ── タッチ状態機械 ─────────────────────────────────────────────
    private enum class TouchState { IDLE, PRESSING, MOVING, DRAGGING }
    private var touchState = TouchState.IDLE

    // ── ボール中心座標（スクリーン座標） ───────────────────────────
    var centerX = initCenterX
        private set
    var centerY = initCenterY
        private set

    // 描画 View が読む公開プロパティ
    val isMoving get() = touchState == TouchState.MOVING
    val isDragging get() = touchState == TouchState.DRAGGING

    /** axis が少なくとも 1 フレーム更新されているか（描画判定用）。 */
    val isAxisReady get() = axisX != 0f || axisY != 0f

    /**
     * ボール外へ出た瞬間に true になり、以後 axis を一切更新しない。
     * resetDragState() で false に戻る。
     */
    var axisLocked = false
        private set

    var dragX = 0f
        private set
    var dragY = 0f
        private set
    var axisX = 0f
        private set
    var axisY = 0f
        private set
    var selectedIndex = -1
        private set
    var isCancelled = false
        private set

    // ── 内部状態 ───────────────────────────────────────────────────
    private val cfg get() = LauncherRepository.config
    private val appSlots get() = LauncherRepository.appSlots

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moveStartCenterX = 0f
    private var moveStartCenterY = 0f
    private var prevSelectedIndex = -1
    private var prevCancelled = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    /** シングルタップ確定後に goHome を遅延発火する Runnable。ダブルタップ検出時にキャンセルする。 */
    private var singleTapRunnable: Runnable? = null
    /** 直前のタップ離し時刻（DOUBLE_TAP_MS 以内に次のタップが来たらダブルタップ）。 */
    private var lastTapUpTime = 0L

    // ── コールバック ───────────────────────────────────────────────
    /** ボール移動中/移動確定時。Service はウィンドウ位置更新 + 描画更新に使う。 */
    var onBallMoved: ((cx: Float, cy: Float) -> Unit)? = null
    /** ボール移動確定時のみ（保存用）。 */
    var onPositionChanged: ((Float, Float) -> Unit)? = null
    var onGoHome: (() -> Unit)? = null
    var onLaunchApp: ((String) -> Unit)? = null
    var onHapticFeedback: (() -> Unit)? = null
    /** 描画 View の invalidate() を要求する。タッチ状態が変化した際に呼ぶ。 */
    var onDrawInvalidate: (() -> Unit)? = null
    /**
     * 長押し移動の開始/終了を通知する。
     * Service はこれを受けて touch Window を視覚ボールと同じサイズにリサイズする。
     *   true  → MOVING 開始（ボールが拡大表示される）
     *   false → MOVING 終了（通常サイズに戻る）
     */
    var onMoveStateChanged: ((isMoving: Boolean) -> Unit)? = null
    /**
     * ダブルタップ検出時に通知する。
     * Service は設定秒数だけオーバーレイを一時非表示にする。
     */
    var onDoubleTapTemporaryHide: (() -> Unit)? = null
    /**
     * DRAGGING 状態の開始 / 終了を通知する。
     *   true  → ドラッグ開始（draw Window をコンテンツ範囲まで拡張させる）
     *   false → ドラッグ終了（draw Window をアイドルサイズへ縮小させる）
     */
    var onDragStateChanged: ((isDragging: Boolean) -> Unit)? = null

    // ── タッチイベント ─────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // ── 円形ヒットテスト ──────────────────────────────────────────
                // touch Window は正方形なので、角への誤タップを防ぐために
                // ローカル座標で視覚円の内側かを判定する。
                // width * 0.5f で整数除算の誤差を排除し、円中心を厳密化
                val lx = event.x - width * 0.5f
                val ly = event.y - height * 0.5f
                // -2f: Window 端の toInt() 切り捨て誤差分を吸収して角の誤タップを防ぐ
                val hitR = getCurrentVisualRadius() - HIT_MARGIN
                if (lx * lx + ly * ly > hitR * hitR) return false

                touchDownX = rawX
                touchDownY = rawY
                lastRawX = rawX
                lastRawY = rawY
                touchState = TouchState.PRESSING
                LauncherRepository.refreshHistoryAsync(context.applicationContext)

                val runnable = Runnable { onLongPressTimer() }
                longPressRunnable = runnable
                handler.postDelayed(runnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchState == TouchState.IDLE) return false
                lastRawX = rawX
                lastRawY = rawY

                when (touchState) {
                    TouchState.MOVING -> applyMovePosition(rawX, rawY)
                    TouchState.DRAGGING -> internalUpdateDrag(rawX, rawY)
                    TouchState.PRESSING -> {
                        val ddx = rawX - touchDownX
                        val ddy = rawY - touchDownY
                        if (ddx * ddx + ddy * ddy > MOVE_CANCEL_PX * MOVE_CANCEL_PX) {
                            startDragMode(rawX, rawY)
                        }
                    }
                    TouchState.IDLE -> return false
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchState == TouchState.IDLE) return false
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null

                when (touchState) {
                    TouchState.DRAGGING -> {
                        val sel = selectedIndex
                        val can = isCancelled
                        resetDragState()
                        touchState = TouchState.IDLE
                        // draw Window をアイドルサイズへ縮小してから再描画
                        onDragStateChanged?.invoke(false)
                        onDrawInvalidate?.invoke()
                        if (!can && sel >= 0) {
                            val pkg = appSlots.getOrNull(sel)?.pinnedApp?.packageName
                            if (pkg != null) onLaunchApp?.invoke(pkg)
                        }
                    }
                    TouchState.MOVING -> {
                        touchState = TouchState.IDLE
                        onMoveStateChanged?.invoke(false)
                        onPositionChanged?.invoke(centerX, centerY)
                        onDrawInvalidate?.invoke()
                    }
                    TouchState.PRESSING -> {
                        val now = SystemClock.elapsedRealtime()
                        val sinceLastTap = now - lastTapUpTime
                        if (lastTapUpTime > 0L && sinceLastTap <= DOUBLE_TAP_MS) {
                            // ── ダブルタップ確定 ────────────────────────────────────────
                            // 保留中の goHome をキャンセルして一時非表示を発火する
                            singleTapRunnable?.let { handler.removeCallbacks(it) }
                            singleTapRunnable = null
                            lastTapUpTime = 0L
                            touchState = TouchState.IDLE
                            onDrawInvalidate?.invoke()
                            onDoubleTapTemporaryHide?.invoke()
                        } else {
                            // ── シングルタップ候補 ──────────────────────────────────────
                            // DOUBLE_TAP_MS 後にダブルタップが来なければ goHome を発火する
                            lastTapUpTime = now
                            touchState = TouchState.IDLE
                            onDrawInvalidate?.invoke()
                            val r = Runnable {
                                singleTapRunnable = null
                                onGoHome?.invoke()
                            }
                            singleTapRunnable = r
                            handler.postDelayed(r, DOUBLE_TAP_MS)
                        }
                    }
                    TouchState.IDLE -> return false
                }
                return true
            }
        }
        return false
    }

    // ── プライベートヘルパー ───────────────────────────────────────

    private fun onLongPressTimer() {
        if (touchState != TouchState.PRESSING) return
        touchState = TouchState.MOVING
        touchDownX = lastRawX
        touchDownY = lastRawY
        moveStartCenterX = centerX
        moveStartCenterY = centerY
        onMoveStateChanged?.invoke(true)
        onDrawInvalidate?.invoke()
    }

    private fun startDragMode(rawX: Float, rawY: Float) {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        // ドラッグ開始時に保留中の goHome もキャンセルする
        singleTapRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable = null
        touchState = TouchState.DRAGGING
        dragX = centerX
        dragY = centerY
        axisX = 0f
        axisY = 0f
        axisLocked = false
        selectedIndex = -1
        isCancelled = false
        prevSelectedIndex = -1
        prevCancelled = false
        // draw Window を先に拡張してから最初の描画を行う
        onDragStateChanged?.invoke(true)
        internalUpdateDrag(rawX, rawY)
    }

    private fun applyMovePosition(rawX: Float, rawY: Float) {
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        centerX = (moveStartCenterX + rawX - touchDownX).coerceIn(0f, screenW)
        centerY = (moveStartCenterY + rawY - touchDownY).coerceIn(0f, screenH)
        onBallMoved?.invoke(centerX, centerY)   // タッチ Window 位置更新 + 描画更新
    }

    private fun internalUpdateDrag(x: Float, y: Float) {
        dragX = x
        dragY = y

        // ── 方向ベクトルをタッチ開始位置基準で計算 ─────────────────────
        // ボール中心基準だと近距離で角度が不安定になる。
        // タッチ開始位置基準にすることで「ユーザーが引っぱった方向」をそのまま軸にする。
        val dx = x - touchDownX
        val dy = y - touchDownY
        val dist = sqrt(dx * dx + dy * dy)

        // デッドゾーン: 24px 未満は axis 未確定のまま描画だけ更新して終了
        val directionDeadZone = 24f
        if (dist < directionDeadZone) {
            onDrawInvalidate?.invoke()
            return
        }

        val targetX = dx / dist
        val targetY = dy / dist

        if (!axisLocked) {
            if (!isAxisReady) {
                // 初回フレームは直接代入
                axisX = targetX
                axisY = targetY
            } else {
                // デッドゾーン超え後はスムージングで収束
                axisX = axisX * (1f - AXIS_SMOOTH_FACTOR) + targetX * AXIS_SMOOTH_FACTOR
                axisY = axisY * (1f - AXIS_SMOOTH_FACTOR) + targetY * AXIS_SMOOTH_FACTOR
                val len = sqrt(axisX * axisX + axisY * axisY)
                if (len > 0.001f) {
                    axisX /= len
                    axisY /= len
                }
            }

            // touchDown から一定距離引いた時点で軸を完全固定
            val freezeDist = cfg.buttonRadiusPx * 1.8f
            if (dist >= freezeDist) {
                axisLocked = true
            }
        }
        // axisLocked == true の場合は axis を一切更新しない

        if (isAxisReady) updateSelection()
        onDrawInvalidate?.invoke()
    }

    /**
     * 現在の視覚ボール半径を返す唯一の正解関数。
     *
     * ・OverlayExpandView.drawIdleBall と完全に同じ式
     * ・OverlayService が touchWindow サイズに使う
     * ・ACTION_DOWN の hit 判定がここから HIT_MARGIN を引く
     *
     * IDLE / MOVING のどちらの状態でも呼べる。
     * 状態変化後に呼べば必ず正しいサイズを返す。
     */
    fun getCurrentVisualRadius(): Float =
        LauncherRepository.config.buttonRadiusPx * (if (isMoving) BALL_MOVING_SCALE else 1.0f)

    private fun resetDragState() {
        dragX = centerX
        dragY = centerY
        axisX = 0f
        axisY = 0f
        axisLocked = false
        selectedIndex = -1
        isCancelled = false
        prevSelectedIndex = -1
        prevCancelled = false
    }

    private fun updateSelection() {
        val relX = dragX - centerX
        val relY = dragY - centerY
        val parallel = relX * axisX + relY * axisY
        val perpX = relX - axisX * parallel
        val perpY = relY - axisY * parallel
        val perp = sqrt(perpX * perpX + perpY * perpY)
        val ratio = perp / maxOf(abs(parallel), cfg.minParallelForRatio)

        isCancelled = ratio > cfg.cancelRatioThreshold || parallel <= 0f
        if (isCancelled) {
            selectedIndex = -1
            if (!prevCancelled) onHapticFeedback?.invoke()
            prevCancelled = true
            prevSelectedIndex = -1
            return
        }
        prevCancelled = false

        var nearestIdx = -1
        var nearestDist = Float.MAX_VALUE
        for (i in 0 until cfg.nodeCount) {
            val nodeDist = cfg.baseOffsetPx + i * cfg.spacingPx
            val reveal = revealProgress(parallel, nodeDist)
            if (reveal < 0.98f) continue
            val d = abs(parallel - nodeDist)
            if (d < nearestDist) {
                nearestDist = d
                nearestIdx = i
            }
        }
        selectedIndex = nearestIdx
        if (selectedIndex >= 0 && selectedIndex != prevSelectedIndex) {
            onHapticFeedback?.invoke()
        }
        prevSelectedIndex = selectedIndex
    }

    private fun revealProgress(parallel: Float, nodeDist: Float): Float {
        val start = nodeDist - cfg.spacingPx * cfg.nodeRevealWindowRatio
        return ((parallel - start) / (nodeDist - start)).coerceIn(0f, 1f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        longPressRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable?.let { handler.removeCallbacks(it) }
    }
}
