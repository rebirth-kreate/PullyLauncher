package com.example.pullyluncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.example.pullyluncher.model.ColorPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** true にするとボール中心に黄色い点と十字を描く（確認用）。 */
private const val DEBUG_ANCHOR = false

private const val BLOB_ORIGIN_DIR_RATIO = 0.0f

/**
 * 長押し移動中のボール視覚スケール。
 * OverlayService の touch Window リサイズにも同じ値を使う（1箇所で管理）。
 */
internal const val BALL_MOVING_SCALE = 1.18f

/** アーク間隔の基準値（度/アイテム）。cfg.revolverArcSpacing を掛けた値が実際の間隔。 */
private const val REVOLVER_ARC_PER_ITEM_BASE_DEG = 60f
/** 全アイテムが広がる最大アーク角（度）。この値を超えると自動縮小。 */
private const val REVOLVER_ARC_MAX_TOTAL_DEG = 240f

/**
 * 描画専用の View。MATCH_PARENT な Window に配置され、描画のみを行う。
 * タッチは一切受け取らない。
 */
class OverlayExpandView(context: Context) : View(context) {

    /** Service が配線する。null の間は onDraw で何も描かない。 */
    var touchView: OverlayTouchView? = null

    private val cfg      get() = LauncherRepository.config
    private val blobRadius get() = cfg.buttonRadiusPx
    private val appSlots get() = LauncherRepository.appSlots

    private var viewScope: CoroutineScope? = null

    // ── Paint オブジェクト ─────────────────────────────────────────
    private val blobFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val nodePaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idleHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── リボルバー用 Paint ─────────────────────────────────────────
    private val revolverNodePaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val revolverSelectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val revolverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color     = Color.WHITE
    }

    // ── ライフサイクル ─────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        viewScope!!.launch {
            LauncherRepository.configFlow.collect { invalidate() }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope?.cancel()
        viewScope = null
    }

    // ── 描画 ───────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val tv = touchView ?: return
        val centerX = tv.centerX
        val centerY = tv.centerY

        // V2: リボルバーメニューが開いている間は専用描画
        if (tv.isPinnedMenuOpen) {
            drawPinnedRevolver(canvas, centerX, centerY, tv)
            drawDebugAnchor(canvas, centerX, centerY)
            return
        }

        if (!tv.isDragging) {
            drawIdleBall(canvas, centerX, centerY)
            drawDebugAnchor(canvas, centerX, centerY)
            return
        }

        val dragX = tv.dragX
        val dragY = tv.dragY
        val isAxisReady  = tv.isAxisReady
        val axisX        = tv.axisX
        val axisY        = tv.axisY
        val selectedIndex = tv.selectedIndex
        val isCancelled  = tv.isCancelled

        val relX = dragX - centerX
        val relY = dragY - centerY

        val rdx: Float
        val rdy: Float
        val rawLen: Float

        if (isAxisReady) {
            rdx    = axisX
            rdy    = axisY
            rawLen = (relX * axisX + relY * axisY).coerceAtLeast(0f)
        } else {
            val dist = sqrt(relX * relX + relY * relY)
            if (dist <= 0.5f) {
                drawIdleBall(canvas, centerX, centerY)
                drawDebugAnchor(canvas, centerX, centerY)
                canvas.restore()
                return
            }
            rdx    = relX / dist
            rdy    = relY / dist
            rawLen = dist
        }

        val perpX = -rdy
        val perpY = rdx

        val blobOriginX = centerX + rdx * blobRadius * BLOB_ORIGIN_DIR_RATIO
        val blobOriginY = centerY + rdy * blobRadius * BLOB_ORIGIN_DIR_RATIO

        val lastNodeDist = cfg.baseOffsetPx + (cfg.nodeCount - 1) * cfg.spacingPx
        val maxBlobLen   = lastNodeDist + cfg.nodeRadiusPx + 20f
        val blobLen      = rawLen.coerceAtMost(maxBlobLen)
        val blobAlpha    = if (isCancelled) 0.28f else 1.0f

        if (blobLen > 4f) {
            drawBlob(canvas, blobOriginX, blobOriginY, rdx, rdy, perpX, perpY, blobLen, blobAlpha)
        } else {
            drawIdleBall(canvas, centerX, centerY)
        }

        if (isAxisReady) {
            val parallel     = relX * axisX + relY * axisY
            val nodeAlphaMult = if (isCancelled) 0.28f else 1.0f
            for (i in 0 until cfg.nodeCount) {
                val nodeDist = cfg.baseOffsetPx + i * cfg.spacingPx
                val reveal   = revealProgress(parallel, nodeDist)
                if (reveal <= 0f) continue
                val animDist  = nodeDist - (1f - reveal) * cfg.nodeRevealBackOffsetPx
                val nodeCx    = blobOriginX + axisX * animDist
                val nodeCy    = blobOriginY + axisY * animDist
                val isSelected = !isCancelled && i == selectedIndex
                val base       = cfg.nodeRadiusPx * (0.55f + 0.45f * reveal)
                val drawRadius = if (isSelected) base + 10f else base
                val nodeAlpha  = (reveal * nodeAlphaMult * 255).toInt().coerceIn(0, 255)

                drawNode(canvas, nodeCx, nodeCy, drawRadius, nodeAlpha, reveal, isSelected)

                val pkg = appSlots.getOrNull(i)?.pinnedApp?.packageName
                val bm  = pkg?.let { LauncherRepository.iconBitmaps[it] }
                if (bm != null) {
                    val iconSize = drawRadius * 1.5f
                    val left = nodeCx - iconSize / 2f
                    val top  = nodeCy - iconSize / 2f
                    iconPaint.alpha = nodeAlpha
                    canvas.drawBitmap(bm, null, RectF(left, top, left + iconSize, top + iconSize), iconPaint)
                }
            }
        }

        drawDebugAnchor(canvas, centerX, centerY)
    }

    // ── V2 リボルバー描画 ─────────────────────────────────────────

    private fun drawPinnedRevolver(canvas: Canvas, cx: Float, cy: Float, tv: OverlayTouchView) {
        val pinned  = LauncherRepository.pinnedApps
        val count   = pinned.size
        if (count == 0) return

        val preset   = ColorPresets.get(cfg.colorPreset)
        val screenW  = width.toFloat()
        val screenH  = height.toFloat()
        val inCancel = tv.inCancelZone

        // リボルバー専用アイコン半径（Pull 側の nodeRadiusPx とは独立）
        val revolverNodeRadius = cfg.nodeRadiusPx * cfg.revolverNodeScale

        // リング半径を config から算出し、画面端で補正
        val baseRingRadius = cfg.buttonRadiusPx * cfg.revolverRingRatio
        val ringRadius = computeRevolverRadius(cx, cy, screenW, screenH, baseRingRadius, revolverNodeRadius)

        // 選択枠の角度（設定から読む）
        val selectorAngleDeg = cfg.selectorPosition.angleDeg
        val selectorAngleRad = Math.toRadians(selectorAngleDeg.toDouble()).toFloat()

        // アーク間隔（設定値で調整可能。最大アーク角を超えると自動縮小）
        val arcPerItem = if (count <= 1) 0f
            else minOf(REVOLVER_ARC_PER_ITEM_BASE_DEG * cfg.revolverArcSpacing,
                       REVOLVER_ARC_MAX_TOTAL_DEG / count.toFloat())

        // ── キャンセル範囲グロー ────────────────────────────────────
        val cancelRadius = cfg.buttonRadiusPx * REVOLVER_CANCEL_ZONE_RATIO
        val cancelAlpha  = if (inCancel) 0.30f else 0.10f
        val cancelColor  = if (inCancel) preset.nodeSelectedColor else Color.WHITE
        val cancelGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, cancelRadius,
                intArrayOf(applyAlphaF(cancelColor, cancelAlpha), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, cancelRadius, cancelGlowPaint)

        // ── アークガイド（アイコンより先に描画して重ならないようにする）──
        if (count >= 2) {
            val guideAlpha = if (inCancel) 0.07f else 0.14f
            val arcGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style      = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeCap  = android.graphics.Paint.Cap.ROUND
                color      = applyAlphaF(Color.WHITE, guideAlpha)
            }
            val totalArcDeg  = minOf(arcPerItem * count, REVOLVER_ARC_MAX_TOTAL_DEG)
            val arcStartAngle = selectorAngleDeg - totalArcDeg / 2f
            val arcRect = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            canvas.drawArc(arcRect, arcStartAngle, totalArcDeg, false, arcGuidePaint)
        }

        // ── 中心ボール ──────────────────────────────────────────────
        drawIdleBall(canvas, cx, cy)

        // ── キャンセルラベル ─────────────────────────────────────────
        if (inCancel) {
            revolverTextPaint.textSize = cfg.buttonRadiusPx * 0.40f
            revolverTextPaint.color    = applyAlphaF(Color.WHITE, 0.90f)
            canvas.drawText("Cancel", cx, cy - cfg.buttonRadiusPx - 14f, revolverTextPaint)
        }

        // 選択枠固定座標（山括弧ポインターの基準点）
        val selX = cx + cos(selectorAngleRad) * ringRadius
        val selY = cy + sin(selectorAngleRad) * ringRadius

        // ── アイテム（アーク配置・循環）────────────────────────────────
        val halfCount = count / 2f
        for (i in 0 until count) {
            var relIdx = i.toFloat() - tv.rotoOffset
            while (relIdx >  halfCount) relIdx -= count
            while (relIdx < -halfCount) relIdx += count

            val itemAngleDeg = selectorAngleDeg - relIdx * arcPerItem
            val itemAngleRad = Math.toRadians(itemAngleDeg.toDouble()).toFloat()
            val itemX = cx + cos(itemAngleRad) * ringRadius
            val itemY = cy + sin(itemAngleRad) * ringRadius

            // 奥行き表現：選択枠から離れるほど小さく・透明に（滑らかに変化）
            val arcProx  = (1f - abs(relIdx) / halfCount.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val extraDim = if (count <= 2) 1f else 0.12f + 0.88f * arcProx

            val isSelected = i == tv.selectedPinnedIndex && !inCancel
            val baseDim = when {
                inCancel   -> 0.28f
                isSelected -> 1.00f
                else       -> 0.65f
            }
            val dimFactor  = baseDim * extraDim
            val itemAlpha  = (dimFactor * 255).toInt().coerceIn(0, 255)

            // 選択中は基準サイズより大きく、それ以外は距離に応じて滑らかに縮小
            val drawRadius = when {
                isSelected -> revolverNodeRadius * 1.18f
                else       -> revolverNodeRadius * (0.68f + 0.24f * arcProx).coerceIn(0.68f, 0.92f)
            }

            // 選択中グロー
            if (isSelected) {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        itemX, itemY, drawRadius * 2.4f,
                        intArrayOf(applyAlphaF(preset.nodeSelectedColor, 0.32f), Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(itemX, itemY, drawRadius * 2.4f, glowPaint)
            }

            // ノード背景
            revolverNodePaint.color = applyAlpha(
                if (isSelected) preset.nodeSelectedColor else preset.nodeIdleColor,
                itemAlpha
            )
            canvas.drawCircle(itemX, itemY, drawRadius, revolverNodePaint)

            // アイコン
            val pkg = pinned.getOrNull(i)?.packageName
            val bm  = pkg?.let { LauncherRepository.iconBitmaps[it] }
            if (bm != null) {
                val iconSize = drawRadius * 1.42f
                iconPaint.alpha = itemAlpha
                canvas.drawBitmap(
                    bm, null,
                    RectF(itemX - iconSize / 2f, itemY - iconSize / 2f,
                          itemX + iconSize / 2f, itemY + iconSize / 2f),
                    iconPaint
                )
            }
        }

        // ── 山括弧ポインター（円形選択枠の代替）────────────────────────
        // 選択位置のさらに外側に配置し、先端が選択中アイコンを指す
        val selectedNodeRadius = revolverNodeRadius * 1.18f
        val ptrOffset  = selectedNodeRadius + 6f       // アイコン端から 6px 離す
        val tipX = selX + cos(selectorAngleRad) * ptrOffset
        val tipY = selY + sin(selectorAngleRad) * ptrOffset
        val armLen  = revolverNodeRadius * 0.72f
        val armHalf = revolverNodeRadius * 0.54f
        val perpCos = -sin(selectorAngleRad)
        val perpSin =  cos(selectorAngleRad)
        val arm1X = tipX + cos(selectorAngleRad) * armLen + perpCos * armHalf
        val arm1Y = tipY + sin(selectorAngleRad) * armLen + perpSin * armHalf
        val arm2X = tipX + cos(selectorAngleRad) * armLen - perpCos * armHalf
        val arm2Y = tipY + sin(selectorAngleRad) * armLen - perpSin * armHalf

        revolverSelectorPaint.color = applyAlphaF(
            if (inCancel) Color.GRAY else preset.nodeSelectedColor,
            if (inCancel) 0.28f else 0.88f
        )
        revolverSelectorPaint.strokeWidth = 2.5f
        revolverSelectorPaint.strokeCap   = Paint.Cap.ROUND
        canvas.drawLine(tipX, tipY, arm1X, arm1Y, revolverSelectorPaint)
        canvas.drawLine(tipX, tipY, arm2X, arm2Y, revolverSelectorPaint)

        // ── 選択中アプリ名 ─────────────────────────────────────────
        if (!inCancel && tv.selectedPinnedIndex in pinned.indices) {
            val label = pinned[tv.selectedPinnedIndex].label
            revolverTextPaint.textSize = (cfg.buttonRadiusPx * 0.36f).coerceIn(24f, 44f)
            revolverTextPaint.color    = applyAlphaF(Color.WHITE, 0.92f)
            val textY = if (cy + cfg.buttonRadiusPx + 52f + revolverTextPaint.textSize < screenH) {
                cy + cfg.buttonRadiusPx + 52f
            } else {
                cy - cfg.buttonRadiusPx - 24f
            }
            val textX = cx.coerceIn(120f, screenW - 120f)
            canvas.drawText(label, textX, textY, revolverTextPaint)
        }
    }

    /**
     * リボルバーリング半径を画面端に合わせて縮小する。
     * nodeRadius パラメータでリボルバー専用アイコンサイズを考慮する。
     */
    private fun computeRevolverRadius(
        cx: Float, cy: Float, screenW: Float, screenH: Float,
        base: Float, nodeRadius: Float
    ): Float {
        val margin = nodeRadius * 1.2f + 8f
        val maxAllowed = minOf(
            cx - margin,
            screenW - cx - margin,
            cy - margin,
            screenH - cy - margin
        )
        return base.coerceAtMost(maxAllowed.coerceAtLeast(base * 0.45f))
    }

    // ── 共通描画ヘルパー ──────────────────────────────────────────

    private fun drawIdleBall(canvas: Canvas, cx: Float, cy: Float) {
        val tv = touchView ?: return
        val isMoving = tv.isMoving
        val r = tv.getCurrentVisualRadius()

        val preset = ColorPresets.get(cfg.colorPreset)
        val alpha  = cfg.ballAlpha

        blobFillPaint.shader = null
        blobFillPaint.color  = applyAlphaF(preset.buttonColor, alpha * (if (isMoving) 0.82f else 1.0f))
        canvas.drawCircle(cx, cy, r, blobFillPaint)

        val hlRadius = r * 0.62f
        val hlCy     = cy - r * 0.22f
        idleHighlightPaint.shader = RadialGradient(
            cx, hlCy, hlRadius,
            intArrayOf(applyAlphaF(Color.WHITE, 0.2f * alpha), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, hlCy, hlRadius, idleHighlightPaint)
    }

    private fun drawDebugAnchor(canvas: Canvas, cx: Float, cy: Float) {
        if (!DEBUG_ANCHOR) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 10f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        canvas.drawLine(cx - 24f, cy, cx + 24f, cy, p)
        canvas.drawLine(cx, cy - 24f, cx, cy + 24f, p)
    }

    private fun applyAlpha(baseColor: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )

    private fun applyAlphaF(baseColor: Int, alphaF: Float): Int =
        applyAlpha(baseColor, (alphaF * 255).toInt())

    private fun drawBlob(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        rdx: Float,
        rdy: Float,
        perpX: Float,
        perpY: Float,
        blobLen: Float,
        blobAlpha: Float
    ) {
        val r      = blobRadius
        val preset = ColorPresets.get(cfg.colorPreset)
        val path   = buildBlobPath(originX, originY, rdx, rdy, perpX, perpY, blobLen)

        blobStrokePaint.color = applyAlphaF(preset.blobStrokeColor, 0.55f * blobAlpha)
        canvas.drawPath(path, blobStrokePaint)

        val tipHalf = r * 0.98f
        val topX    = originX - rdx * r
        val topY    = originY - rdy * r
        val tipMidX = originX + rdx * (blobLen + tipHalf)
        val tipMidY = originY + rdy * (blobLen + tipHalf)

        blobFillPaint.shader = LinearGradient(
            topX, topY, tipMidX, tipMidY,
            intArrayOf(
                applyAlphaF(preset.buttonColor, blobAlpha),
                applyAlphaF(preset.buttonColor, 0.84f * blobAlpha),
                applyAlphaF(preset.buttonColor, 0.55f * blobAlpha)
            ),
            floatArrayOf(0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, blobFillPaint)
        blobFillPaint.shader = null

        val glowCx     = originX - rdx * (r * 0.28f)
        val glowCy     = originY - rdy * (r * 0.28f)
        val glowRadius = r * 0.75f
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                glowCx, glowCy, glowRadius,
                intArrayOf(applyAlphaF(Color.WHITE, 0.16f * blobAlpha), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(glowCx, glowCy, glowRadius, hlPaint)
    }

    private fun drawNode(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        drawRadius: Float,
        alpha: Int,
        reveal: Float,
        isSelected: Boolean
    ) {
        val preset = ColorPresets.get(cfg.colorPreset)
        if (isSelected) {
            val glowR = drawRadius * 2.6f
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, glowR,
                    intArrayOf(applyAlphaF(preset.nodeSelectedColor, 0.30f * reveal), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, glowR, glowPaint)
            nodePaint.color = applyAlpha(preset.nodeSelectedColor, alpha)
            canvas.drawCircle(cx, cy, drawRadius, nodePaint)
            nodePaint.color = applyAlpha(preset.nodeSelectedCoreColor, alpha)
            canvas.drawCircle(cx, cy, 10f * reveal.coerceAtLeast(0.35f), nodePaint)
        } else {
            nodePaint.color = applyAlpha(preset.nodeIdleColor, alpha)
            canvas.drawCircle(cx, cy, drawRadius, nodePaint)
            nodePaint.color = applyAlpha(preset.nodeIdleCoreColor, alpha)
            canvas.drawCircle(cx, cy, 10f * reveal.coerceAtLeast(0.35f), nodePaint)
        }
    }

    private fun buildBlobPath(
        cx: Float,
        cy: Float,
        rdx: Float,
        rdy: Float,
        perpX: Float,
        perpY: Float,
        rawLen: Float
    ): android.graphics.Path {
        val r       = blobRadius
        val tipHalf = r * 0.98f
        val k       = 0.5523f
        val kr      = r * k

        val topX  = cx - rdx * r
        val topY  = cy - rdy * r
        val lEqX  = cx - perpX * r
        val lEqY  = cy - perpY * r
        val rEqX  = cx + perpX * r
        val rEqY  = cy + perpY * r
        val lTipX = cx + rdx * rawLen - perpX * tipHalf
        val lTipY = cy + rdy * rawLen - perpY * tipHalf
        val rTipX = cx + rdx * rawLen + perpX * tipHalf
        val rTipY = cy + rdy * rawLen + perpY * tipHalf
        val tMidX = cx + rdx * (rawLen + tipHalf)
        val tMidY = cy + rdy * (rawLen + tipHalf)

        val rCp1x = rEqX + rdx * (rawLen * 0.72f)
        val rCp1y = rEqY + rdy * (rawLen * 0.72f)
        val rCp2x = rTipX - rdx * (rawLen * 0.08f)
        val rCp2y = rTipY - rdy * (rawLen * 0.08f)
        val lCp1x = lTipX - rdx * (rawLen * 0.08f)
        val lCp1y = lTipY - rdy * (rawLen * 0.08f)
        val lCp2x = lEqX + rdx * (rawLen * 0.72f)
        val lCp2y = lEqY + rdy * (rawLen * 0.72f)

        val tipK  = tipHalf * k
        val tR1x  = rTipX + rdx * tipK
        val tR1y  = rTipY + rdy * tipK
        val tR2x  = tMidX + perpX * tipK
        val tR2y  = tMidY + perpY * tipK
        val tL1x  = tMidX - perpX * tipK
        val tL1y  = tMidY - perpY * tipK
        val tL2x  = lTipX + rdx * tipK
        val tL2y  = lTipY + rdy * tipK

        return android.graphics.Path().apply {
            moveTo(lEqX, lEqY)
            cubicTo(
                lEqX - rdx * kr, lEqY - rdy * kr,
                topX - perpX * kr, topY - perpY * kr,
                topX, topY
            )
            cubicTo(
                topX + perpX * kr, topY + perpY * kr,
                rEqX - rdx * kr, rEqY - rdy * kr,
                rEqX, rEqY
            )
            cubicTo(rCp1x, rCp1y, rCp2x, rCp2y, rTipX, rTipY)
            cubicTo(tR1x, tR1y, tR2x, tR2y, tMidX, tMidY)
            cubicTo(tL1x, tL1y, tL2x, tL2y, lTipX, lTipY)
            cubicTo(lCp1x, lCp1y, lCp2x, lCp2y, lEqX, lEqY)
            close()
        }
    }

    private fun revealProgress(parallel: Float, nodeDist: Float): Float {
        val start = nodeDist - cfg.spacingPx * cfg.nodeRevealWindowRatio
        return ((parallel - start) / (nodeDist - start)).coerceIn(0f, 1f)
    }
}
