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
import com.example.pullyluncher.model.AppSlot
import com.example.pullyluncher.model.ColorPresets
import com.example.pullyluncher.model.LauncherUiConfig
import kotlin.math.abs
import kotlin.math.sqrt

// ── チューニング定数 ──────────────────────────────────────────────────

/**
 * true にするとアンカー点（= 渡されたボール中心）に黄色い点と十字を描く。
 * 赤いボールの中心と一致しているか視覚確認するためのデバッグ用。
 * 確認が済んだら false に戻すこと。
 */
private const val DEBUG_ANCHOR = false

/**
 * ブロブ幾何学の起点（blobOrigin）を、ボール中心からドラッグ方向へずらす割合。
 *
 *   0.0  = ボールの数学的中心が起点（ballCenter = blobOrigin）
 *   正値  = ブロブ起点がドラッグ方向へ移動（ブロブがボール前面から始まるように見える）
 */
private const val BLOB_ORIGIN_DIR_RATIO = 0.0f

// ─────────────────────────────────────────────────────────────────────

/**
 * フローティングボタンから引っ張ったときにオーバーレイ上に展開する UI View。
 *
 * OverlayService から FLAG_NOT_TOUCHABLE の MATCH_PARENT Window として追加される。
 * FLAG_LAYOUT_IN_SCREEN は付与しないため、canvas(0,0) = オーバーレイ窓の左上。
 * ballCenterX/Y も updateDrag の座標も同じオーバーレイ座標系で渡される。
 *
 * 責務の分離:
 *   ballCenterX/Y  … Service から受け取るボールの真の中心（DEBUG_ANCHOR で可視化）
 *   blobOriginX/Y  … ブロブ幾何学の起点（onDraw 内で動的に計算）
 *
 * @param ballCenterX  ボール中心 X（オーバーレイ座標: layoutParams.x + size/2）
 * @param ballCenterY  ボール中心 Y（オーバーレイ座標: layoutParams.y + size/2）
 * @param blobRadiusPx ブロブ球体部分の半径（BallView の実ピクセル半径 = buttonSizePx/2）
 * @param config       共通設定値（LauncherRepository.config から渡す）
 * @param appSlots     アプリスロット一覧（LauncherRepository.appSlots から渡す）
 */
class OverlayExpandView(
    context: Context,
    private val ballCenterX: Float,
    private val ballCenterY: Float,
    private val blobRadiusPx: Float,
    private val config: LauncherUiConfig = LauncherUiConfig(),
    private val appSlots: List<AppSlot>  = emptyList()
) : View(context) {

    // ── ドラッグ状態 ────────────────────────────────────────────────
    private var isDragging      = false
    private var directionLocked = false
    private var dragX           = 0f
    private var dragY           = 0f
    private var axisX           = 0f
    private var axisY           = 0f

    /** サービス側がアプリ起動判定に使う: 選択中のノードインデックス (-1 = 未選択) */
    var selectedIndex = -1
        private set

    /** サービス側がアプリ起動判定に使う: キャンセル状態かどうか */
    var isCancelled = false
        private set

    // ── ペイント ─────────────────────────────────────────────────────
    private val blobFillPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── カラーヘルパー ─────────────────────────────────────────────
    /** baseColor の RGB を保持しつつ alpha だけ差し替えた ARGB int を返す */
    private fun applyAlpha(baseColor: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255),
            Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

    private fun applyAlphaF(baseColor: Int, alphaF: Float): Int =
        applyAlpha(baseColor, (alphaF * 255).toInt())

    // ── 公開 API ────────────────────────────────────────────────────

    fun startDrag() {
        isDragging      = true
        directionLocked = false
        dragX           = ballCenterX
        dragY           = ballCenterY
        axisX           = 0f
        axisY           = 0f
        selectedIndex   = -1
        isCancelled     = false
        invalidate()
    }

    /** 座標はオーバーレイ座標系（rawY - statusBarHeight を渡すこと） */
    fun updateDrag(x: Float, y: Float) {
        if (!isDragging) return
        dragX = x
        dragY = y

        val dx   = x - ballCenterX
        val dy   = y - ballCenterY
        val dist = sqrt(dx * dx + dy * dy)

        if (!directionLocked && dist >= config.lockDistancePx) {
            axisX           = dx / dist
            axisY           = dy / dist
            directionLocked = true
        }
        if (directionLocked) updateSelection()
        invalidate()
    }

    fun endDrag() {
        isDragging      = false
        directionLocked = false
        selectedIndex   = -1
        isCancelled     = false
        invalidate()
    }

    // ── 選択ロジック ────────────────────────────────────────────────

    private fun updateSelection() {
        val relX     = dragX - ballCenterX
        val relY     = dragY - ballCenterY
        val parallel = relX * axisX + relY * axisY
        val perpX    = relX - axisX * parallel
        val perpY    = relY - axisY * parallel
        val perp     = sqrt(perpX * perpX + perpY * perpY)
        val ratio    = perp / maxOf(abs(parallel), config.minParallelForRatio)

        isCancelled = ratio > config.cancelRatioThreshold || parallel <= 0f
        if (isCancelled) { selectedIndex = -1; return }

        var nearestIdx  = -1
        var nearestDist = Float.MAX_VALUE
        for (i in 0 until config.nodeCount) {
            val nodeDist = config.baseOffsetPx + i * config.spacingPx
            val reveal   = revealProgress(parallel, nodeDist)
            if (reveal < 0.98f) continue
            val d = abs(parallel - nodeDist)
            if (d < nearestDist) { nearestDist = d; nearestIdx = i }
        }
        selectedIndex = nearestIdx
    }

    private fun revealProgress(parallel: Float, nodeDist: Float): Float {
        val start = nodeDist - config.spacingPx * config.nodeRevealWindowRatio
        return ((parallel - start) / (nodeDist - start)).coerceIn(0f, 1f)
    }

    // ── 描画 ────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (!isDragging) return

        val relX = dragX - ballCenterX
        val relY = dragY - ballCenterY

        // ── ドラッグ方向を決定 ──────────────────────────────────────
        val rdx: Float
        val rdy: Float
        val rawLen: Float

        if (directionLocked) {
            rdx    = axisX
            rdy    = axisY
            rawLen = (relX * axisX + relY * axisY).coerceAtLeast(0f)
        } else {
            val dist = sqrt(relX * relX + relY * relY)
            if (dist <= 0.5f) {
                drawBallAt(canvas, ballCenterX, ballCenterY, 1f)
                drawDebugAnchor(canvas)
                return
            }
            rdx    = relX / dist
            rdy    = relY / dist
            rawLen = dist
        }

        val perpX = -rdy
        val perpY =  rdx

        // ── ブロブ起点を計算 ────────────────────────────────────────
        val blobOriginX = ballCenterX + rdx * blobRadiusPx * BLOB_ORIGIN_DIR_RATIO
        val blobOriginY = ballCenterY + rdy * blobRadiusPx * BLOB_ORIGIN_DIR_RATIO

        // ── ブロブを描画 ────────────────────────────────────────────
        val lastNodeDist = config.baseOffsetPx + (config.nodeCount - 1) * config.spacingPx
        val maxBlobLen   = lastNodeDist + config.nodeRadiusPx + 20f
        val blobLen      = rawLen.coerceAtMost(maxBlobLen)
        val blobAlpha    = if (isCancelled) 0.28f else 1.0f

        if (blobLen > 4f) {
            drawBlob(canvas, blobOriginX, blobOriginY, rdx, rdy, perpX, perpY, blobLen, blobAlpha)
        } else {
            drawBallAt(canvas, blobOriginX, blobOriginY, blobAlpha)
        }

        // ── ノードを描画 ────────────────────────────────────────────
        if (directionLocked) {
            val parallel      = relX * axisX + relY * axisY
            val nodeAlphaMult = if (isCancelled) 0.28f else 1.0f
            for (i in 0 until config.nodeCount) {
                val nodeDist = config.baseOffsetPx + i * config.spacingPx
                val reveal   = revealProgress(parallel, nodeDist)
                if (reveal <= 0f) continue
                val animDist   = nodeDist - (1f - reveal) * config.nodeRevealBackOffsetPx
                val nodeCx     = blobOriginX + axisX * animDist
                val nodeCy     = blobOriginY + axisY * animDist
                val isSelected = !isCancelled && i == selectedIndex
                val base       = config.nodeRadiusPx * (0.55f + 0.45f * reveal)
                val drawRadius = if (isSelected) base + 10f else base
                val nodeAlpha  = (reveal * nodeAlphaMult * 255).toInt().coerceIn(0, 255)

                drawNode(canvas, nodeCx, nodeCy, drawRadius, nodeAlpha, reveal, isSelected)

                // アイコン描画（LauncherRepository の Bitmap キャッシュを参照）
                val pkg = appSlots.getOrNull(i)?.pinnedApp?.packageName
                val bm  = pkg?.let { LauncherRepository.iconBitmaps[it] }
                if (bm != null) {
                    val iconSize = drawRadius * 1.5f
                    val left     = nodeCx - iconSize / 2f
                    val top      = nodeCy - iconSize / 2f
                    iconPaint.alpha = nodeAlpha
                    canvas.drawBitmap(bm, null, RectF(left, top, left + iconSize, top + iconSize), iconPaint)
                }
            }
        }

        drawDebugAnchor(canvas)
    }

    // ── デバッグ: ballCenter にアンカー点を表示 ─────────────────────
    private fun drawDebugAnchor(canvas: Canvas) {
        if (!DEBUG_ANCHOR) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }
        canvas.drawCircle(ballCenterX, ballCenterY, 10f, p)
        p.style       = Paint.Style.STROKE
        p.strokeWidth = 2f
        canvas.drawLine(ballCenterX - 24f, ballCenterY, ballCenterX + 24f, ballCenterY, p)
        canvas.drawLine(ballCenterX, ballCenterY - 24f, ballCenterX, ballCenterY + 24f, p)
    }

    // ── 部品描画 ────────────────────────────────────────────────────

    /** ブロブ未形成時のフォールバック（通常ボール） */
    private fun drawBallAt(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        val preset = ColorPresets.get(config.colorPreset)
        blobFillPaint.shader = null
        blobFillPaint.color  = applyAlphaF(preset.buttonColor, alpha)
        canvas.drawCircle(cx, cy, blobRadiusPx, blobFillPaint)
    }

    private fun drawBlob(
        canvas: Canvas,
        originX: Float, originY: Float,
        rdx: Float, rdy: Float,
        perpX: Float, perpY: Float,
        blobLen: Float, blobAlpha: Float
    ) {
        val preset = ColorPresets.get(config.colorPreset)
        val path   = buildBlobPath(originX, originY, rdx, rdy, perpX, perpY, blobLen)

        // 輪郭
        blobStrokePaint.color = applyAlphaF(preset.blobStrokeColor, 0.55f * blobAlpha)
        canvas.drawPath(path, blobStrokePaint)

        // グラデーション塗り
        val tipHalf = blobRadiusPx * 0.98f
        val topX    = originX - rdx * blobRadiusPx
        val topY    = originY - rdy * blobRadiusPx
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

        // 上部ハイライト
        val glowCx     = originX - rdx * (blobRadiusPx * 0.28f)
        val glowCy     = originY - rdy * (blobRadiusPx * 0.28f)
        val glowRadius = blobRadiusPx * 0.75f
        val hlPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                glowCx, glowCy, glowRadius,
                intArrayOf(
                    applyAlphaF(Color.WHITE, 0.16f * blobAlpha),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(glowCx, glowCy, glowRadius, hlPaint)
    }

    private fun drawNode(
        canvas: Canvas,
        cx: Float, cy: Float,
        drawRadius: Float, alpha: Int,
        reveal: Float, isSelected: Boolean
    ) {
        val preset = ColorPresets.get(config.colorPreset)
        if (isSelected) {
            val glowR     = drawRadius * 2.6f
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, glowR,
                    intArrayOf(
                        applyAlphaF(preset.nodeSelectedColor, 0.30f * reveal),
                        Color.TRANSPARENT
                    ),
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

    // ── ブロブ Path ─────────────────────────────────────────────────

    private fun buildBlobPath(
        cx: Float, cy: Float,       // = blobOriginX/Y（ballCenter とは別）
        rdx: Float, rdy: Float,
        perpX: Float, perpY: Float,
        rawLen: Float
    ): android.graphics.Path {
        val r       = blobRadiusPx
        val tipHalf = r * 0.98f
        val k       = 0.5523f
        val kr      = r * k

        val topX  = cx - rdx * r
        val topY  = cy - rdy * r
        val lEqX  = cx - perpX * r;      val lEqY  = cy - perpY * r
        val rEqX  = cx + perpX * r;      val rEqY  = cy + perpY * r
        val lTipX = cx + rdx * rawLen - perpX * tipHalf
        val lTipY = cy + rdy * rawLen - perpY * tipHalf
        val rTipX = cx + rdx * rawLen + perpX * tipHalf
        val rTipY = cy + rdy * rawLen + perpY * tipHalf
        val tMidX = cx + rdx * (rawLen + tipHalf)
        val tMidY = cy + rdy * (rawLen + tipHalf)

        val rCp1x = rEqX  + rdx * (rawLen * 0.72f); val rCp1y = rEqY  + rdy * (rawLen * 0.72f)
        val rCp2x = rTipX - rdx * (rawLen * 0.08f); val rCp2y = rTipY - rdy * (rawLen * 0.08f)
        val lCp1x = lTipX - rdx * (rawLen * 0.08f); val lCp1y = lTipY - rdy * (rawLen * 0.08f)
        val lCp2x = lEqX  + rdx * (rawLen * 0.72f); val lCp2y = lEqY  + rdy * (rawLen * 0.72f)

        val tipK  = tipHalf * k
        val tR1x  = rTipX + rdx   * tipK;  val tR1y  = rTipY + rdy   * tipK
        val tR2x  = tMidX + perpX * tipK;  val tR2y  = tMidY + perpY * tipK
        val tL1x  = tMidX - perpX * tipK;  val tL1y  = tMidY - perpY * tipK
        val tL2x  = lTipX + rdx   * tipK;  val tL2y  = lTipY + rdy   * tipK

        return android.graphics.Path().apply {
            moveTo(lEqX, lEqY)
            cubicTo(lEqX - rdx * kr, lEqY - rdy * kr,
                    topX - perpX * kr, topY - perpY * kr, topX, topY)
            cubicTo(topX + perpX * kr, topY + perpY * kr,
                    rEqX - rdx * kr, rEqY - rdy * kr, rEqX, rEqY)
            cubicTo(rCp1x, rCp1y, rCp2x, rCp2y, rTipX, rTipY)
            cubicTo(tR1x, tR1y, tR2x, tR2y, tMidX, tMidY)
            cubicTo(tL1x, tL1y, tL2x, tL2y, lTipX, lTipY)
            cubicTo(lCp1x, lCp1y, lCp2x, lCp2y, lEqX, lEqY)
            close()
        }
    }
}
