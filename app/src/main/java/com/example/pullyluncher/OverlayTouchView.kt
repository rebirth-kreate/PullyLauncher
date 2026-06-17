package com.example.pullyluncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LONG_PRESS_MS   = 400L
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
 * 推奨: 0.18
 */
private const val AXIS_SMOOTH_FACTOR = 0.18f

// ── V2 リボルバー定数 ──────────────────────────────────────────────
/**
 * キャンセル範囲半径 = ボール半径 × この値。
 * 指がこの範囲内にある状態で離すとキャンセル扱いになる。後から調整可能。
 */
internal const val REVOLVER_CANCEL_ZONE_RATIO = 1.2f

/**
 * 近距離（Pully と横方向同位置）での基準回転速度。
 * cfg.revolverSpeedScale=1.0 のときの実効値。後から調整可能。
 */
private const val REVOLVER_SPEED_NEAR = 2.4f

/**
 * 遠距離（Pully から横方向へ MAX_DIST 離れた位置）での基準回転速度。
 * cfg.revolverSpeedScale=1.0 のときの実効値。後から調整可能。
 */
private const val REVOLVER_SPEED_FAR = 0.3f

/**
 * 速度が FAR 値になる Pully 中心からの横方向距離（px）。後から調整可能。
 */
internal const val REVOLVER_SPEED_MAX_DIST_PX = 300f

/**
 * 指を 100px 上下したときの基準移動量（アイテム計算の分母）。後から調整可能。
 */
private const val REVOLVER_BASE_PX_PER_ITEM = 100f

/**
 * 選択枠付近でスナップ抵抗がかかる範囲（アイテム間隔の分数）。
 * 0=スナップなし / 0.5=最大範囲。後から調整可能。
 */
private const val REVOLVER_SNAP_ZONE_FRAC   = 0.28f

/**
 * スナップの抵抗強度（0=無効 / 1=完全停止）。初期値は弱め。後から調整可能。
 */
private const val REVOLVER_SNAP_STRENGTH    = 0.35f

/**
 * 指を離した後の選択枠へのスナップアニメーション時間（ms）。後から調整可能。
 */
private const val REVOLVER_SNAP_DURATION_MS = 120L

/**
 * ダブルタップ後の 2 回目タップをこれ以上ホールドすると本体移動モードへ入る（ms）。
 * 後から調整可能。
 */
private const val DOUBLE_TAP_HOLD_MS = 250L

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
 *
 * ── ジェスチャー分岐 ────────────────────────────────────────────────
 *   短タップ           → goHome（ホーム画面へ戻る）
 *   ダブルタップ        → 一時非表示
 *   MOVE_CANCEL_PX 超移動 → DRAGGING（V1: 履歴アプリ引き出し）
 *   長押し 500ms・pinnedApps 非空  → PINNED_MENU（V2: リボルバー選択）
 *   長押し 500ms・pinnedApps 空    → MOVING（V1: ボール位置変更）
 */
class OverlayTouchView(
    context: Context,
    initCenterX: Float,
    initCenterY: Float,
) : View(context) {

    // ── タッチ状態機械 ─────────────────────────────────────────────
    private enum class TouchState { IDLE, PRESSING, MOVING, DRAGGING, PINNED_MENU }
    private var touchState = TouchState.IDLE

    // ── ボール中心座標（スクリーン座標） ───────────────────────────
    var centerX = initCenterX
        private set
    var centerY = initCenterY
        private set

    // 描画 View が読む公開プロパティ
    val isMoving   get() = touchState == TouchState.MOVING
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

    // ── V2 リボルバー公開状態 ──────────────────────────────────────
    /** リボルバーメニューが開いているか（OverlayExpandView が読む）*/
    var isPinnedMenuOpen = false
        private set
    /** 現在の回転オフセット（0..count の連続値、OverlayExpandView が読む）*/
    var rotoOffset = 0f
        private set
    /** 現在選択中の固定アプリインデックス（-1 = 未選択 or キャンセル中）*/
    var selectedPinnedIndex = -1
        private set
    /** 指がキャンセル範囲内にあるか（描画ハイライト用）*/
    var inCancelZone = false
        private set

    // ── 内部状態 ───────────────────────────────────────────────────
    private val cfg      get() = LauncherRepository.config
    private val appSlots get() = LauncherRepository.appSlots

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moveStartCenterX = 0f
    private var moveStartCenterY = 0f
    private var prevSelectedIndex = -1
    private var prevCancelled = false
    private var lastPinnedHapticIndex = -1

    // ── スナップアニメーション ────────────────────────────────────────
    private var snapRunnable: Runnable? = null
    private var snapPendingLaunchPkg: String? = null
    private var snapStartRoto = 0f
    private var snapTargetLinear = 0f
    private var snapStartTime = 0L

    // ── ダブルタップホールド（本体移動） ──────────────────────────────
    private var doubleTapHoldRunnable: Runnable? = null

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
    /** 固定アプリをリボルバーで選択したときに通知する（packageName を渡す）*/
    var onLaunchPinnedApp: ((String) -> Unit)? = null
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

    // ── タッチイベント ─────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // ── 円形ヒットテスト ──────────────────────────────────────────
                val lx = event.x - width * 0.5f
                val ly = event.y - height * 0.5f
                val hitR = getCurrentVisualRadius() - HIT_MARGIN
                if (lx * lx + ly * ly > hitR * hitR) return false

                touchDownX = rawX
                touchDownY = rawY
                lastRawX = rawX
                lastRawY = rawY
                touchState = TouchState.PRESSING
                LauncherRepository.refreshHistoryAsync(context.applicationContext)

                val now = SystemClock.elapsedRealtime()
                if (lastTapUpTime > 0L && now - lastTapUpTime <= DOUBLE_TAP_MS) {
                    // 2 回目タップがダブルタップ窓内 → ホールドタイマー開始（長押しは起動しない）
                    singleTapRunnable?.let { handler.removeCallbacks(it) }
                    singleTapRunnable = null
                    val holdR = Runnable { onDoubleTapHoldTimer() }
                    doubleTapHoldRunnable = holdR
                    handler.postDelayed(holdR, DOUBLE_TAP_HOLD_MS)
                } else {
                    lastTapUpTime = 0L
                    val runnable = Runnable { onLongPressTimer() }
                    longPressRunnable = runnable
                    handler.postDelayed(runnable, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchState == TouchState.IDLE) return false

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
                    TouchState.PINNED_MENU -> applyRevolverMove(rawX, rawY)
                    TouchState.IDLE -> return false
                }
                lastRawX = rawX
                lastRawY = rawY
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
                    TouchState.PINNED_MENU -> {
                        val pinned = LauncherRepository.pinnedApps
                        val shouldLaunch = !inCancelZone && selectedPinnedIndex in pinned.indices
                        val pkgToLaunch  = if (shouldLaunch) pinned[selectedPinnedIndex].packageName else null
                        startRevolverSnap(pkgToLaunch)
                    }
                    TouchState.PRESSING -> {
                        val now = SystemClock.elapsedRealtime()
                        if (doubleTapHoldRunnable != null) {
                            // ── ダブルタップ（ホールド前に離した）→ 一時非表示 ────────────
                            doubleTapHoldRunnable?.let { handler.removeCallbacks(it) }
                            doubleTapHoldRunnable = null
                            lastTapUpTime = 0L
                            touchState = TouchState.IDLE
                            onDrawInvalidate?.invoke()
                            onDoubleTapTemporaryHide?.invoke()
                        } else {
                            // ── シングルタップ候補 ──────────────────────────────────────
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
        val pinned = LauncherRepository.pinnedApps
        if (pinned.isNotEmpty()) {
            // V2: 固定アプリが登録されていればリボルバーメニューを開く
            touchState          = TouchState.PINNED_MENU
            isPinnedMenuOpen    = true
            rotoOffset          = 0f
            selectedPinnedIndex = 0
            inCancelZone        = false
            lastPinnedHapticIndex = -1
            onHapticFeedback?.invoke()
            onDrawInvalidate?.invoke()
        } else {
            // 固定アプリなし: 振動のみ。移動モードへは入らない
            // 次の ACTION_UP は IDLE 分岐で無視される
            touchState = TouchState.IDLE
            onHapticFeedback?.invoke()
        }
    }

    private fun applyRevolverMove(rawX: Float, rawY: Float) {
        val dx = rawX - touchDownX
        val dy = rawY - touchDownY
        val dist = sqrt(dx * dx + dy * dy)
        val cancelRadius = cfg.buttonRadiusPx * REVOLVER_CANCEL_ZONE_RATIO
        inCancelZone = dist < cancelRadius

        val pinned = LauncherRepository.pinnedApps
        val count  = pinned.size
        if (!inCancelZone && count > 0) {
            // Pully 中心からの横方向距離で速度を決定する。
            // 上下移動量 → 回転入力、横方向距離 → 速度制御（上下移動しても横位置が同じなら速度は変化しない）
            val horizontalDist = abs(rawX - centerX)
            val normalizedHorizDist = (horizontalDist / REVOLVER_SPEED_MAX_DIST_PX).coerceIn(0f, 1f)
            val speedFactor = (REVOLVER_SPEED_NEAR + (REVOLVER_SPEED_FAR - REVOLVER_SPEED_NEAR) * normalizedHorizDist) * cfg.revolverSpeedScale
            // 上方向（負の deltaY）でオフセット増加 → 次のアイテムへ
            val rawDelta = -(rawY - lastRawY) * speedFactor / REVOLVER_BASE_PX_PER_ITEM
            // 選択枠付近でのスナップ抵抗（中心に近づくほど減速）
            val nearestIntF  = rotoOffset.roundToInt().toFloat()
            val distFromSnap = abs(rotoOffset - nearestIntF)
            val snapFactor   = if (distFromSnap < REVOLVER_SNAP_ZONE_FRAC) {
                val t = distFromSnap / REVOLVER_SNAP_ZONE_FRAC
                1f - REVOLVER_SNAP_STRENGTH * (1f - t)
            } else 1f
            val deltaOffset = rawDelta * snapFactor
            rotoOffset = ((rotoOffset + deltaOffset) % count + count) % count

            val newIdx = rotoOffset.roundToInt() % count
            if (newIdx != selectedPinnedIndex) {
                selectedPinnedIndex = newIdx
                if (lastPinnedHapticIndex != newIdx) {
                    onHapticFeedback?.invoke()
                    lastPinnedHapticIndex = newIdx
                }
            }
        }
        onDrawInvalidate?.invoke()
    }

    private fun startDragMode(rawX: Float, rawY: Float) {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        singleTapRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable = null
        doubleTapHoldRunnable?.let { handler.removeCallbacks(it) }
        doubleTapHoldRunnable = null
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
        internalUpdateDrag(rawX, rawY)
    }

    /** リボルバーメニューを指定アプリへスナップしてから終了する。pkgToLaunch=null ならキャンセル。 */
    private fun startRevolverSnap(pkgToLaunch: String?) {
        val count = LauncherRepository.pinnedApps.size
        if (count == 0) { finishRevolverMenu(pkgToLaunch); return }
        val rawTarget = rotoOffset.roundToInt()
        val snapDelta = rawTarget.toFloat() - rotoOffset  // [-0.5, 0.5]
        if (abs(snapDelta) < 0.02f) { finishRevolverMenu(pkgToLaunch); return }

        snapStartRoto        = rotoOffset
        snapTargetLinear     = rotoOffset + snapDelta
        snapPendingLaunchPkg = pkgToLaunch
        snapStartTime        = SystemClock.elapsedRealtime()

        val r = object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.elapsedRealtime() - snapStartTime).toFloat()
                val t       = (elapsed / REVOLVER_SNAP_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                val tEased  = 1f - (1f - t) * (1f - t)  // ease-out quadratic
                rotoOffset  = snapStartRoto + (snapTargetLinear - snapStartRoto) * tEased
                rotoOffset  = ((rotoOffset % count) + count) % count
                onDrawInvalidate?.invoke()
                if (t < 1f) {
                    handler.postDelayed(this, 16)
                } else {
                    snapRunnable = null
                    val pkg = snapPendingLaunchPkg
                    snapPendingLaunchPkg = null
                    finishRevolverMenu(pkg)
                }
            }
        }
        snapRunnable = r
        handler.post(r)
    }

    /** リボルバーメニューの状態をリセットし、必要に応じてアプリを起動する。 */
    private fun finishRevolverMenu(pkgToLaunch: String?) {
        isPinnedMenuOpen      = false
        touchState            = TouchState.IDLE
        rotoOffset            = 0f
        selectedPinnedIndex   = -1
        inCancelZone          = false
        lastPinnedHapticIndex = -1
        onDrawInvalidate?.invoke()
        if (pkgToLaunch != null) onLaunchPinnedApp?.invoke(pkgToLaunch)
    }

    /** ダブルタップホールドが確定 → 本体移動モードへ入る。 */
    private fun onDoubleTapHoldTimer() {
        if (touchState != TouchState.PRESSING) return
        doubleTapHoldRunnable = null
        lastTapUpTime         = 0L
        touchState            = TouchState.MOVING
        touchDownX            = lastRawX
        touchDownY            = lastRawY
        moveStartCenterX      = centerX
        moveStartCenterY      = centerY
        onHapticFeedback?.invoke()
        onMoveStateChanged?.invoke(true)
        onDrawInvalidate?.invoke()
    }

    private fun applyMovePosition(rawX: Float, rawY: Float) {
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        centerX = (moveStartCenterX + rawX - touchDownX).coerceIn(0f, screenW)
        centerY = (moveStartCenterY + rawY - touchDownY).coerceIn(0f, screenH)
        onBallMoved?.invoke(centerX, centerY)
    }

    private fun internalUpdateDrag(x: Float, y: Float) {
        dragX = x
        dragY = y

        val dx = x - touchDownX
        val dy = y - touchDownY
        val dist = sqrt(dx * dx + dy * dy)

        val directionDeadZone = 24f
        if (dist < directionDeadZone) {
            onDrawInvalidate?.invoke()
            return
        }

        val targetX = dx / dist
        val targetY = dy / dist

        if (!axisLocked) {
            if (!isAxisReady) {
                axisX = targetX
                axisY = targetY
            } else {
                axisX = axisX * (1f - AXIS_SMOOTH_FACTOR) + targetX * AXIS_SMOOTH_FACTOR
                axisY = axisY * (1f - AXIS_SMOOTH_FACTOR) + targetY * AXIS_SMOOTH_FACTOR
                val len = sqrt(axisX * axisX + axisY * axisY)
                if (len > 0.001f) {
                    axisX /= len
                    axisY /= len
                }
            }

            val freezeDist = cfg.buttonRadiusPx * 1.8f
            if (dist >= freezeDist) {
                axisLocked = true
            }
        }

        if (isAxisReady) updateSelection()
        onDrawInvalidate?.invoke()
    }

    /**
     * 現在の視覚ボール半径を返す唯一の正解関数。
     *
     * ・OverlayExpandView.drawIdleBall と完全に同じ式
     * ・OverlayService が touchWindow サイズに使う
     * ・ACTION_DOWN の hit 判定がここから HIT_MARGIN を引く
     */
    fun getCurrentVisualRadius(): Float =
        LauncherRepository.config.buttonRadiusPx * (if (isMoving) BALL_MOVING_SCALE else 1.0f)

    /**
     * オーバーレイ非表示時など、外部からジェスチャーを強制キャンセルする。
     * PINNED_MENU 中に applyHide() が呼ばれた場合の状態リセットに使う。
     */
    fun cancelGesture() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        singleTapRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable = null
        doubleTapHoldRunnable?.let { handler.removeCallbacks(it) }
        doubleTapHoldRunnable = null
        snapRunnable?.let { handler.removeCallbacks(it) }
        snapRunnable = null
        snapPendingLaunchPkg = null
        lastTapUpTime = 0L
        if (isPinnedMenuOpen) {
            isPinnedMenuOpen      = false
            rotoOffset            = 0f
            selectedPinnedIndex   = -1
            inCancelZone          = false
            lastPinnedHapticIndex = -1
        }
        resetDragState()
        touchState = TouchState.IDLE
        // onDrawInvalidate は呼ばない（非表示処理中なので不要）
    }

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
        doubleTapHoldRunnable?.let { handler.removeCallbacks(it) }
        snapRunnable?.let { handler.removeCallbacks(it) }
    }
}
