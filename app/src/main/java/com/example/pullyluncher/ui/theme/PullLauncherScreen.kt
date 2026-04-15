package com.example.pullyluncher.ui.theme

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.pullyluncher.LauncherRepository
import com.example.pullyluncher.R
import com.example.pullyluncher.logic.*
import com.example.pullyluncher.model.AppSlot
import com.example.pullyluncher.model.ColorPresets
import com.example.pullyluncher.model.LauncherUiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val ColorBackground      = Color(0xFF040814)
private val ColorBgGradTop       = Color(0xFF08111E)
private val ColorBgGradMid       = Color(0xFF050A14)
private val ColorBgGradBot       = Color(0xFF03060D)
private val ColorCenterGlow      = Color(0xFF2D5B9A)
private val ColorButtonHighlight = Color.White

private val TopSafeArea = 84.dp

@Composable
fun PullLauncherScreen(
    config: LauncherUiConfig,
    onOpenSettings: () -> Unit,
    refreshNonce: Int = 0
) {
    val context = LocalContext.current

    val preset                = ColorPresets.get(config.colorPreset)
    val ColorButtonFill       = Color(preset.buttonColor)
    val ColorBlobStroke       = Color(preset.blobStrokeColor)
    val ColorNodeIdle         = Color(preset.nodeIdleColor)
    val ColorNodeIdleCore     = Color(preset.nodeIdleCoreColor)
    val ColorNodeSelected     = Color(preset.nodeSelectedColor)
    val ColorNodeSelectedCore = Color(preset.nodeSelectedCoreColor)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    var gestureAccepted by remember { mutableStateOf(false) }
    var isDragging      by remember { mutableStateOf(false) }
    var directionLocked by remember { mutableStateOf(false) }

    var dragCurrent by remember { mutableStateOf(Offset.Zero) }
    var axis        by remember { mutableStateOf(Offset.Zero) }

    var selectedIndex by remember { mutableIntStateOf(-1) }
    var isCancelled   by remember { mutableStateOf(false) }
    var resultText    by remember { mutableStateOf("中央の丸を引っ張ってください") }

    var appSlots by remember { mutableStateOf<List<AppSlot>>(emptyList()) }
    val iconCache = remember { mutableStateMapOf<String, ImageBitmap>() }

    LaunchedEffect(refreshNonce) {
        withContext(Dispatchers.IO) {
            if (LauncherRepository.allApps.isEmpty()) {
                LauncherRepository.loadAll(context)
            } else {
                LauncherRepository.refreshHistory(context)
            }
        }
        appSlots = LauncherRepository.computeSlots(config.nodeCount)
        for ((pkg, bm) in LauncherRepository.iconBitmaps) {
            iconCache[pkg] = bm.asImageBitmap()
        }
    }

    val center = Offset(
        x = canvasSize.width / 2f,
        y = canvasSize.height / 2f
    )

    fun resetDragState() {
        gestureAccepted = false
        isDragging      = false
        directionLocked = false
        dragCurrent     = Offset.Zero
        axis            = Offset.Zero
        selectedIndex   = -1
        isCancelled     = false
    }

    fun revealProgressForNode(parallel: Float, nodeDistance: Float): Float {
        val revealStart = nodeDistance - config.spacingPx * config.nodeRevealWindowRatio
        return ((parallel - revealStart) / (nodeDistance - revealStart)).coerceIn(0f, 1f)
    }

    fun updateSelection() {
        if (!directionLocked) return

        val relative            = dragCurrent - center
        val parallel            = dot(relative, axis)
        val perpendicularVector = relative - (axis * parallel)
        val perpendicular       = perpendicularVector.length()
        val ratio               = perpendicular / maxOf(abs(parallel), config.minParallelForRatio)

        isCancelled = ratio > config.cancelRatioThreshold || parallel <= 0f

        if (isCancelled) {
            selectedIndex = -1
            return
        }

        var nearestIndex    = -1
        var nearestDistance = Float.MAX_VALUE

        for (i in 0 until config.nodeCount) {
            val nodeDistance = config.baseOffsetPx + i * config.spacingPx
            val reveal = revealProgressForNode(parallel, nodeDistance)
            if (reveal < 0.98f) continue

            val d = abs(parallel - nodeDistance)
            if (d < nearestDistance) {
                nearestDistance = d
                nearestIndex    = i
            }
        }

        selectedIndex = nearestIndex
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBackground)
            .onSizeChanged { canvasSize = it }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = TopSafeArea)
                .pointerInput(canvasSize, config) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            if (canvasSize == IntSize.Zero) return@detectDragGestures
                            val distFromCenter = (startOffset - center).length()
                            if (distFromCenter <= config.buttonRadiusPx) {
                                gestureAccepted = true
                                isDragging      = true
                                directionLocked = false
                                dragCurrent     = startOffset
                                axis            = Offset.Zero
                                selectedIndex   = -1
                                isCancelled     = false
                                resultText      = "ドラッグ中..."
                            } else {
                                gestureAccepted = false
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (!gestureAccepted) return@detectDragGestures
                            change.consume()
                            dragCurrent += dragAmount

                            val dragVector   = dragCurrent - center
                            val dragDistance = dragVector.length()

                            if (!directionLocked && dragDistance >= config.lockDistancePx) {
                                axis            = dragVector.normalized()
                                directionLocked = true
                            }

                            if (directionLocked) updateSelection()
                        },
                        onDragEnd = {
                            if (!gestureAccepted) return@detectDragGestures
                            when {
                                !directionLocked -> resultText = "ロック距離が足りません"
                                isCancelled      -> resultText = "cancel"
                                selectedIndex >= 0 -> {
                                    val app = appSlots.getOrNull(selectedIndex)?.pinnedApp
                                    if (app != null) {
                                        val launchIntent = context.packageManager
                                            .getLaunchIntentForPackage(app.packageName)
                                        if (launchIntent != null) {
                                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(launchIntent)
                                        }
                                        resultText = app.label
                                    } else {
                                        resultText = "selected: ${selectedIndex + 1}"
                                    }
                                }
                                else -> resultText = "未選択"
                            }
                            resetDragState()
                        },
                        onDragCancel = {
                            if (gestureAccepted) resultText = "cancel"
                            resetDragState()
                        }
                    )
                }
        ) {
            if (canvasSize == IntSize.Zero) return@Canvas

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(ColorBgGradTop, ColorBgGradMid, ColorBgGradBot)
                )
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ColorCenterGlow.copy(alpha = config.backgroundGlow),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.65f
                ),
                radius = size.minDimension * 0.65f,
                center = center
            )

            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = config.edgeDarkness)
                    ),
                    center = center,
                    radius = size.maxDimension * 0.72f
                )
            )

            if (isDragging) {
                val railDir = if (directionLocked) axis
                else (dragCurrent - center).normalized()

                val rawLen = if (directionLocked) {
                    dot(dragCurrent - center, axis).coerceAtLeast(0f)
                } else {
                    (dragCurrent - center).length()
                }

                val lastNodeDist = config.baseOffsetPx + (config.nodeCount - 1) * config.spacingPx
                val maxBlobLen   = lastNodeDist + config.nodeRadiusPx + 20f
                val blobLen      = rawLen.coerceAtMost(maxBlobLen)
                val blobAlpha    = if (isCancelled) 0.28f else 1.0f

                if (railDir.length() > 0.5f && blobLen > 4f) {
                    val tipHalf = config.buttonRadiusPx * 0.98f
                    val blobPath = buildBlobPath(
                        center, railDir, blobLen, config.buttonRadiusPx, tipHalf
                    )

                    drawPath(
                        path = blobPath,
                        color = ColorBlobStroke.copy(alpha = 0.55f * blobAlpha),
                        style = Stroke(width = 2.5f)
                    )

                    val topPt    = center - railDir * config.buttonRadiusPx
                    val tipMidPt = center + railDir * (blobLen + tipHalf)
                    drawPath(
                        path = blobPath,
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.00f to ColorButtonFill.copy(alpha = blobAlpha),
                                0.55f to ColorButtonFill.copy(alpha = 0.84f * blobAlpha),
                                1.00f to ColorButtonFill.copy(alpha = 0.55f * blobAlpha)
                            ),
                            start = topPt,
                            end = tipMidPt
                        )
                    )

                    val glowCenter = center - railDir * (config.buttonRadiusPx * 0.28f)
                    val glowRadius = config.buttonRadiusPx * 0.75f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ColorButtonHighlight.copy(alpha = 0.16f * blobAlpha),
                                Color.Transparent
                            ),
                            center = glowCenter,
                            radius = glowRadius
                        ),
                        radius = glowRadius,
                        center = glowCenter
                    )
                } else {
                    drawCircle(color = ColorButtonFill.copy(alpha = config.ballAlpha), radius = config.buttonRadiusPx, center = center)
                    drawCircle(
                        color = ColorButtonHighlight.copy(alpha = 0.22f * config.ballAlpha),
                        radius = config.buttonRadiusPx - 18f,
                        center = center
                    )
                }
            } else {
                drawCircle(color = ColorButtonFill.copy(alpha = config.ballAlpha), radius = config.buttonRadiusPx, center = center)
                drawCircle(
                    color = ColorButtonHighlight.copy(alpha = 0.22f * config.ballAlpha),
                    radius = config.buttonRadiusPx - 18f,
                    center = center
                )
            }

            if (isDragging && directionLocked) {
                val relative = dragCurrent - center
                val parallel = dot(relative, axis)

                val nodeAlphaMult = if (isCancelled) 0.28f else 1.0f

                for (i in 0 until config.nodeCount) {
                    val nodeDistance = config.baseOffsetPx + i * config.spacingPx
                    val reveal = revealProgressForNode(parallel, nodeDistance)
                    if (reveal <= 0f) continue

                    val animatedDist = nodeDistance - (1f - reveal) * config.nodeRevealBackOffsetPx
                    val nodeCenter   = center + axis * animatedDist
                    val isSelected   = !isCancelled && i == selectedIndex

                    val baseRadius = config.nodeRadiusPx * (0.55f + 0.45f * reveal)
                    val drawRadius = if (isSelected) baseRadius + 10f else baseRadius

                    val fillColor = if (isSelected) {
                        ColorNodeSelected.copy(alpha = reveal * nodeAlphaMult)
                    } else {
                        ColorNodeIdle.copy(alpha = reveal * nodeAlphaMult)
                    }
                    val coreColor = if (isSelected) {
                        ColorNodeSelectedCore.copy(alpha = reveal * nodeAlphaMult)
                    } else {
                        ColorNodeIdleCore.copy(alpha = reveal * nodeAlphaMult)
                    }

                    if (isSelected) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ColorNodeSelected.copy(alpha = 0.30f * reveal),
                                    Color.Transparent
                                ),
                                center = nodeCenter,
                                radius = drawRadius * 2.6f
                            ),
                            radius = drawRadius * 2.6f,
                            center = nodeCenter
                        )
                    }

                    drawCircle(color = fillColor, radius = drawRadius, center = nodeCenter)

                    val icon = appSlots.getOrNull(i)?.pinnedApp?.packageName?.let { iconCache[it] }
                    if (icon != null) {
                        val iconPx = (drawRadius * 1.5f).roundToInt().coerceAtLeast(1)
                        val left   = (nodeCenter.x - iconPx / 2f).roundToInt()
                        val top    = (nodeCenter.y - iconPx / 2f).roundToInt()
                        drawImage(
                            image = icon,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(icon.width, icon.height),
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize(iconPx, iconPx),
                            alpha = reveal * nodeAlphaMult
                        )
                    } else {
                        drawCircle(
                            color = coreColor,
                            radius = 10f * reveal.coerceAtLeast(0.35f),
                            center = nodeCenter
                        )
                    }
                }
            }
        }

        Text(
            text = "PullyLuncher MVP+",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .zIndex(1f)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 2.dp)
                .sizeIn(minWidth = 96.dp, minHeight = 64.dp)
                .zIndex(1f),
            contentAlignment = Alignment.Center
        ) {
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_open),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Text(
            text = resultText,
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .zIndex(1f)
        )
    }
}

private fun buildBlobPath(
    center: Offset,
    railDir: Offset,
    rawLen: Float,
    buttonRadius: Float,
    tipHalf: Float,
): Path {
    val perp = Offset(-railDir.y, railDir.x)
    val k = 0.5523f

    val top      = center - railDir * buttonRadius
    val leftEq   = center - perp * buttonRadius
    val rightEq  = center + perp * buttonRadius
    val leftTip  = center + railDir * rawLen - perp * tipHalf
    val rightTip = center + railDir * rawLen + perp * tipHalf
    val tipMid   = center + railDir * rawLen + railDir * tipHalf

    val r = buttonRadius * k

    val rightCp1 = rightEq  + railDir * (rawLen * 0.72f)
    val rightCp2 = rightTip - railDir * (rawLen * 0.08f)
    val leftCp1  = leftTip  - railDir * (rawLen * 0.08f)
    val leftCp2  = leftEq   + railDir * (rawLen * 0.72f)

    val tipK = tipHalf * k
    val tcpR1 = rightTip + railDir * tipK
    val tcpR2 = tipMid   + perp * tipK
    val tcpL1 = tipMid   - perp * tipK
    val tcpL2 = leftTip  + railDir * tipK

    return Path().apply {
        moveTo(leftEq.x, leftEq.y)
        cubicTo(
            leftEq.x - railDir.x * r, leftEq.y - railDir.y * r,
            top.x - perp.x * r, top.y - perp.y * r,
            top.x, top.y
        )
        cubicTo(
            top.x + perp.x * r, top.y + perp.y * r,
            rightEq.x - railDir.x * r, rightEq.y - railDir.y * r,
            rightEq.x, rightEq.y
        )
        cubicTo(rightCp1.x, rightCp1.y, rightCp2.x, rightCp2.y, rightTip.x, rightTip.y)
        cubicTo(tcpR1.x, tcpR1.y, tcpR2.x, tcpR2.y, tipMid.x, tipMid.y)
        cubicTo(tcpL1.x, tcpL1.y, tcpL2.x, tcpL2.y, leftTip.x, leftTip.y)
        cubicTo(leftCp1.x, leftCp1.y, leftCp2.x, leftCp2.y, leftEq.x, leftEq.y)
        close()
    }
}