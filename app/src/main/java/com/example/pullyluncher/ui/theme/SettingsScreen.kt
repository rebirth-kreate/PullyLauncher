@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.pullyluncher.ui.theme

import android.content.Context
import android.content.Intent
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.pullyluncher.LauncherRepository
import com.example.pullyluncher.R
import com.example.pullyluncher.data.UsageHistoryRepository
import com.example.pullyluncher.model.AppEntry
import com.example.pullyluncher.model.ColorPresets
import com.example.pullyluncher.model.LauncherUiConfig
import com.example.pullyluncher.model.SelectorPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ─── Design tokens ─────────────────────────────────────────────────────────────
private val BgColor       = Color(0xFF0C1018)
private val CardColor     = Color(0xFF141923)
private val CardBorderCol = Color(0xFF1E2A3A)
private val AccentColor   = Color(0xFF88C0D0)
private val TextColor     = Color.White
private val HintColor     = Color(0xFF7A9BB0)
private val DangerColor   = Color(0xFFBF5A5A)

// ─── Dialog routing ────────────────────────────────────────────────────────────
private enum class FeatureDialog {
    PINNED_APPS, HIDDEN_APPS, COLOR, CAPTURE_MODE, USAGE_HISTORY, FLOATING
}

// ═══════════════════════════════════════════════════════════════════════════════
// SettingsScreen — main entry point
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    onClose: () -> Unit,
    hasOverlayPermission: Boolean   = false,
    isOverlayRunning: Boolean       = false,
    onRequestPermission: () -> Unit = {},
    onStartOverlay: () -> Unit      = {},
    onStopOverlay: () -> Unit       = {}
) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    var localPinned  by remember { mutableStateOf(LauncherRepository.pinnedApps) }
    var activeDialog by remember { mutableStateOf<FeatureDialog?>(null) }

    LaunchedEffect(Unit) {
        if (LauncherRepository.allApps.isEmpty()) {
            withContext(Dispatchers.IO) { LauncherRepository.loadAll(context) }
            localPinned = LauncherRepository.pinnedApps
        }
    }

    // ── Feature dialogs ──────────────────────────────────────────────────────
    when (activeDialog) {
        FeatureDialog.PINNED_APPS -> PinnedAppsDialogContent(
            localPinned    = localPinned,
            onPinnedChange = { list ->
                localPinned = list
                LauncherRepository.setPinnedApps(context, list)
            },
            allApps   = LauncherRepository.allApps,
            onDismiss = { activeDialog = null }
        )
        FeatureDialog.HIDDEN_APPS -> HiddenAppsDialogContent(
            config         = config,
            onConfigChange = onConfigChange,
            allApps        = LauncherRepository.allApps,
            onDismiss      = { activeDialog = null }
        )
        FeatureDialog.COLOR -> ColorDialogContent(
            config         = config,
            onConfigChange = onConfigChange,
            onDismiss      = { activeDialog = null }
        )
        FeatureDialog.CAPTURE_MODE -> CaptureModeDialogContent(
            config         = config,
            onConfigChange = onConfigChange,
            onDismiss      = { activeDialog = null }
        )
        FeatureDialog.USAGE_HISTORY -> UsageHistoryDialogContent(
            context   = context,
            onDismiss = { activeDialog = null }
        )
        FeatureDialog.FLOATING -> FloatingDialogContent(
            hasOverlayPermission = hasOverlayPermission,
            isOverlayRunning     = isOverlayRunning,
            onRequestPermission  = onRequestPermission,
            onStartOverlay       = onStartOverlay,
            onStopOverlay        = onStopOverlay,
            onDismiss            = { activeDialog = null }
        )
        null -> Unit
    }

    // ── Layout ───────────────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().background(BgColor)) {

        // Header
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .background(Color(0xFF080C12))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text       = "Pully Launcher",
                style      = MaterialTheme.typography.titleMedium,
                color      = AccentColor,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.settings_close), color = HintColor)
            }
        }

        // Tab strip (fixed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0E15))
        ) {
            listOf("PULL", "REVOLVER").forEachIndexed { i, label ->
                val selected = pagerState.currentPage == i
                Column(
                    modifier            = Modifier
                        .weight(1f)
                        .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text          = label,
                        color         = if (selected) AccentColor else HintColor,
                        fontWeight    = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize      = 12.sp,
                        letterSpacing = 1.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.40f)
                            .height(2.dp)
                            .background(
                                if (selected) AccentColor else Color.Transparent,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }

        // Pages
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0    -> PullPage(
                    config         = config,
                    onConfigChange = onConfigChange,
                    onOpenDialog   = { activeDialog = it }
                )
                else -> RevolverPage(
                    config         = config,
                    onConfigChange = onConfigChange,
                    localPinned    = localPinned,
                    onOpenDialog   = { activeDialog = it }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Pull page — fixed preview at top, scrollable settings below
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PullPage(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    onOpenDialog: (FeatureDialog) -> Unit
) {
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor   = Color(0xFF141923),
            title = { Text("Pull 設定のリセット", color = TextColor) },
            text  = { Text("Pull 設定をデフォルト値に戻します。", color = HintColor) },
            confirmButton = {
                TextButton(onClick = {
                    onConfigChange(config.copy(
                        buttonRadiusPx       = 80f,
                        nodeRadiusPx         = 44f,
                        spacingPx            = 140f,
                        ballAlpha            = 1.0f,
                        baseOffsetPx         = 180f,
                        lockDistancePx       = 120f,
                        cancelRatioThreshold = 0.40f,
                        nodeCount            = 5,
                        temporaryHideSeconds = 5
                    ))
                    showResetConfirm = false
                }) { Text("リセット", color = DangerColor) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("キャンセル", color = HintColor)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── 固定プレビュー（スクロール外）────────────────────────────────
        PullPreviewCard(
            config   = config,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF080E16))
        )

        HorizontalDivider(color = Color(0xFF1A2535), thickness = 1.dp)

        // ── スクロール可能な設定リスト ──────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            SectionHeader("基本設定")

            SliderSettingCard(
                label         = stringResource(R.string.setting_button_radius),
                hint          = stringResource(R.string.hint_button_radius),
                value         = config.buttonRadiusPx,
                range         = 40f..140f,
                step          = 5f,
                format        = { "%.0fpx".format(it) },
                onValueChange = { onConfigChange(config.copy(buttonRadiusPx = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_node_radius),
                hint          = stringResource(R.string.hint_node_radius),
                value         = config.nodeRadiusPx,
                range         = 20f..80f,
                step          = 2f,
                format        = { "%.0fpx".format(it) },
                onValueChange = { onConfigChange(config.copy(nodeRadiusPx = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_spacing),
                hint          = stringResource(R.string.hint_spacing),
                value         = config.spacingPx,
                range         = 80f..220f,
                step          = 5f,
                format        = { "%.0fpx".format(it) },
                onValueChange = { onConfigChange(config.copy(spacingPx = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_ball_alpha),
                hint          = stringResource(R.string.hint_ball_alpha),
                value         = config.ballAlpha,
                range         = 0.3f..1.0f,
                step          = 0.05f,
                format        = { "%.0f%%".format(it * 100f) },
                onValueChange = { onConfigChange(config.copy(ballAlpha = it)) }
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader("挙動設定")

            SliderSettingCard(
                label         = stringResource(R.string.setting_base_offset),
                hint          = stringResource(R.string.hint_base_offset),
                value         = config.baseOffsetPx,
                range         = 100f..280f,
                step          = 10f,
                format        = { "%.0fpx".format(it) },
                onValueChange = { onConfigChange(config.copy(baseOffsetPx = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_lock_distance),
                hint          = stringResource(R.string.hint_lock_distance),
                value         = config.lockDistancePx,
                range         = 60f..220f,
                step          = 10f,
                format        = { "%.0fpx".format(it) },
                onValueChange = { onConfigChange(config.copy(lockDistancePx = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_cancel_ratio),
                hint          = stringResource(R.string.hint_cancel_ratio),
                value         = config.cancelRatioThreshold,
                range         = 0.15f..0.80f,
                step          = 0.05f,
                format        = { "%.0f%%".format(it * 100f) },
                onValueChange = { onConfigChange(config.copy(cancelRatioThreshold = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_node_count),
                hint          = stringResource(R.string.hint_node_count),
                value         = config.nodeCount.toFloat(),
                range         = 3f..10f,
                step          = 1f,
                format        = { "%d".format(it.roundToInt()) },
                onValueChange = { onConfigChange(config.copy(nodeCount = it.roundToInt())) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_temporary_hide_seconds),
                hint          = stringResource(R.string.hint_temporary_hide_seconds),
                value         = config.temporaryHideSeconds.toFloat(),
                range         = 1f..10f,
                step          = 1f,
                format        = { "%d秒".format(it.roundToInt()) },
                onValueChange = { onConfigChange(config.copy(temporaryHideSeconds = it.roundToInt())) }
            )

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick  = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(1.dp, DangerColor.copy(alpha = 0.5f)),
                shape    = RoundedCornerShape(10.dp)
            ) {
                Text("Pull 設定をリセット", color = DangerColor)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("関連機能")
            FeatureList(onOpenDialog = onOpenDialog)
            Spacer(Modifier.height(48.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Revolver page — fixed preview at top, scrollable settings below
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun RevolverPage(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    localPinned: List<AppEntry>,
    onOpenDialog: (FeatureDialog) -> Unit
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor   = Color(0xFF141923),
            title = { Text("Revolver 設定のリセット", color = TextColor) },
            text  = { Text("Revolver 設定をデフォルト値に戻します。", color = HintColor) },
            confirmButton = {
                TextButton(onClick = {
                    onConfigChange(config.copy(
                        revolverRingRatio  = 2.4f,
                        revolverSpeedScale = 1.0f,
                        revolverNodeScale  = 1.0f,
                        revolverArcSpacing = 1.0f,
                        selectorPosition   = SelectorPosition.RIGHT,
                        edgeDarkness       = 0.42f,
                        backgroundGlow     = 0.20f
                    ))
                    showResetConfirm = false
                }) { Text("リセット", color = DangerColor) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("キャンセル", color = HintColor)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── 固定プレビュー（スクロール外）────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF080E16))
        ) {
            RevolverPreview(
                config     = config,
                pinnedApps = localPinned,
                modifier   = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            )
            Text(
                text      = "長押しで Revolver を起動 / 上下スワイプで回転",
                color     = HintColor,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, top = 2.dp)
            )
        }

        HorizontalDivider(color = Color(0xFF1A2535), thickness = 1.dp)

        // ── スクロール可能な設定リスト ──────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            SectionHeader("基本設定")

            SliderSettingCard(
                label         = stringResource(R.string.setting_revolver_diameter),
                hint          = stringResource(R.string.hint_revolver_diameter),
                value         = config.revolverRingRatio,
                range         = 1.5f..6.0f,
                step          = 0.1f,
                format        = { "%.1f×".format(it) },
                onValueChange = { onConfigChange(config.copy(revolverRingRatio = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_revolver_speed),
                hint          = stringResource(R.string.hint_revolver_speed),
                value         = config.revolverSpeedScale,
                range         = 0.5f..2.0f,
                step          = 0.1f,
                format        = { "%d%%".format((it * 100).roundToInt()) },
                onValueChange = { onConfigChange(config.copy(revolverSpeedScale = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_revolver_icon_size),
                hint          = stringResource(R.string.hint_revolver_icon_size),
                value         = config.revolverNodeScale,
                range         = 0.5f..1.8f,
                step          = 0.1f,
                format        = { "%.1f×".format(it) },
                onValueChange = { onConfigChange(config.copy(revolverNodeScale = it)) }
            )
            SliderSettingCard(
                label         = stringResource(R.string.setting_revolver_spacing),
                hint          = stringResource(R.string.hint_revolver_spacing),
                value         = config.revolverArcSpacing,
                range         = 0.4f..1.8f,
                step          = 0.1f,
                format        = { "%.1f×".format(it) },
                onValueChange = { onConfigChange(config.copy(revolverArcSpacing = it)) }
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader("選択位置")
            SelectorPositionCard(config = config, onConfigChange = onConfigChange)

            Spacer(Modifier.height(8.dp))

            // ── 詳細（折りたたみ）────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("詳細設定", color = HintColor, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(if (advancedExpanded) "▲" else "▼", color = HintColor, fontSize = 10.sp)
            }

            if (advancedExpanded) {
                SliderSettingCard(
                    label         = stringResource(R.string.setting_edge_darkness),
                    hint          = stringResource(R.string.hint_edge_darkness),
                    value         = config.edgeDarkness,
                    range         = 0.0f..0.9f,
                    step          = 0.05f,
                    format        = { "%.0f%%".format(it * 100f) },
                    onValueChange = { onConfigChange(config.copy(edgeDarkness = it)) }
                )
                SliderSettingCard(
                    label         = stringResource(R.string.setting_background_glow),
                    hint          = stringResource(R.string.hint_background_glow),
                    value         = config.backgroundGlow,
                    range         = 0.0f..0.6f,
                    step          = 0.05f,
                    format        = { "%.0f%%".format(it * 100f) },
                    onValueChange = { onConfigChange(config.copy(backgroundGlow = it)) }
                )
            }

            HorizontalDivider(color = Color(0xFF1A2030), modifier = Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick  = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(1.dp, DangerColor.copy(alpha = 0.5f)),
                shape    = RoundedCornerShape(10.dp)
            ) {
                Text("Revolver 設定をリセット", color = DangerColor)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("関連機能")
            FeatureList(onOpenDialog = onOpenDialog)
            Spacer(Modifier.height(48.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Pull preview — mirrors buildBlobPath from OverlayExpandView for accuracy
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PullPreviewCard(config: LauncherUiConfig, modifier: Modifier = Modifier) {
    val preset    = ColorPresets.get(config.colorPreset)
    val nodeCount = config.nodeCount.coerceAtMost(8)

    Canvas(modifier = modifier) {
        drawIntoCanvas { cc ->
            val nc      = cc.nativeCanvas
            val dpScale = density
            val pw      = size.width
            val ph      = size.height

            // Fit entire content horizontally
            val lastNodeDist = config.baseOffsetPx + config.spacingPx * (nodeCount - 1)
            val neededW      = config.buttonRadiusPx + lastNodeDist + config.nodeRadiusPx + 24f
            val neededH      = config.buttonRadiusPx * 2.2f + config.nodeRadiusPx * 2.2f
            val sc           = min(pw * 0.90f / neededW, ph * 0.80f / neededH).coerceIn(0.20f, 2.5f)

            val totalW = (config.buttonRadiusPx + lastNodeDist + config.nodeRadiusPx) * sc
            val cx     = pw / 2f - totalW / 2f + config.buttonRadiusPx * sc
            val cy     = ph / 2f

            val r     = config.buttonRadiusPx * sc
            val sNR   = config.nodeRadiusPx * sc
            val sBase = config.baseOffsetPx * sc
            val sSpc  = config.spacingPx * sc

            // Blob length at ~60% extension for preview
            val blobLen = (sBase + sSpc * (nodeCount - 1) * 0.60f)
                .coerceAtMost(sBase + sSpc * (nodeCount - 1) + sNR)

            // ── Blob path — same math as OverlayExpandView.buildBlobPath ────
            // Direction: rightward (rdx=1, rdy=0, perpX=0, perpY=1)
            val tipHalf = r * 0.98f
            val k       = 0.5523f
            val kr      = r * k
            val tipK    = tipHalf * k

            val blobPath = android.graphics.Path().apply {
                moveTo(cx, cy - r)
                // Left arc of ball (top → left equator → back)
                cubicTo(cx - kr, cy - r,  cx - r, cy - kr, cx - r, cy)
                cubicTo(cx - r, cy + kr,  cx - kr, cy + r, cx, cy + r)
                // Taper from bottom of ball to lower tip
                cubicTo(
                    cx + blobLen * 0.72f, cy + r,
                    cx + blobLen * 0.92f, cy + tipHalf,
                    cx + blobLen, cy + tipHalf
                )
                // Rounded tip (lower → mid → upper)
                cubicTo(
                    cx + blobLen + tipK, cy + tipHalf,
                    cx + blobLen + tipHalf, cy + tipK,
                    cx + blobLen + tipHalf, cy
                )
                cubicTo(
                    cx + blobLen + tipHalf, cy - tipK,
                    cx + blobLen + tipK, cy - tipHalf,
                    cx + blobLen, cy - tipHalf
                )
                // Taper from upper tip back to top of ball
                cubicTo(
                    cx + blobLen * 0.92f, cy - tipHalf,
                    cx + blobLen * 0.72f, cy - r,
                    cx, cy - r
                )
                close()
            }

            // Stroke
            val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style       = AndroidPaint.Style.STROKE
                strokeWidth = 1.5f
                color       = previewArgb(preset.blobStrokeColor, 0.55f)
            }
            nc.drawPath(blobPath, strokePaint)

            // Fill: gradient from ball to tip (matches drawBlob)
            val fillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    cx - r, cy, cx + blobLen + tipHalf, cy,
                    intArrayOf(
                        previewArgb(preset.buttonColor, 1.0f),
                        previewArgb(preset.buttonColor, 0.84f),
                        previewArgb(preset.buttonColor, 0.50f)
                    ),
                    floatArrayOf(0f, 0.55f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            nc.drawPath(blobPath, fillPaint)

            // ── Nodes (matches drawNode) ─────────────────────────────────────
            val selectedIdx = nodeCount / 2
            val nodePaint   = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
            for (i in 0 until nodeCount) {
                val nx         = cx + sBase + i * sSpc
                val ny         = cy
                val isSelected = i == selectedIdx
                val dimF       = (1.0f - abs(i - selectedIdx) * 0.14f).coerceIn(0.30f, 1.0f)
                val nR         = sNR * (if (isSelected) 1.12f else 0.86f)

                if (isSelected) {
                    val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            nx, ny, nR * 2.6f,
                            intArrayOf(
                                previewArgb(preset.nodeSelectedColor, 0.28f),
                                android.graphics.Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    nc.drawCircle(nx, ny, nR * 2.6f, glowPaint)
                }

                nodePaint.color = previewArgb(
                    if (isSelected) preset.nodeSelectedColor else preset.nodeIdleColor,
                    if (isSelected) 1.0f else dimF
                )
                nc.drawCircle(nx, ny, nR, nodePaint)

                // Core dot (matches drawNode core circle)
                nodePaint.color = previewArgb(
                    if (isSelected) preset.nodeSelectedCoreColor else preset.nodeIdleCoreColor,
                    if (isSelected) 1.0f else dimF * 0.8f
                )
                nc.drawCircle(nx, ny, 6f * sc.coerceAtLeast(0.35f), nodePaint)
            }

            // ── Center ball (over blob) ──────────────────────────────────────
            val ballPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = previewArgb(preset.buttonColor, config.ballAlpha)
            }
            nc.drawCircle(cx, cy, r, ballPaint)

            // Ball highlight (matches drawIdleBall radial glow)
            val hlRadius = r * 0.62f
            val hlCy     = cy - r * 0.22f
            val hlPaint  = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, hlCy, hlRadius,
                    intArrayOf(
                        previewArgb(0xFFFFFFFF.toInt(), 0.20f * config.ballAlpha),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            nc.drawCircle(cx, hlCy, hlRadius, hlPaint)

            // Label
            val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color     = previewArgb(0xFFFFFFFF.toInt(), 0.22f)
                textSize  = 10.5f * dpScale
                textAlign = AndroidPaint.Align.CENTER
            }
            nc.drawText("引っ張ってアプリを選択", pw / 2f, ph - 10f * dpScale, textPaint)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Revolver preview — pointer matches drawPinnedRevolver in OverlayExpandView
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun RevolverPreview(
    config: LauncherUiConfig,
    pinnedApps: List<AppEntry>,
    modifier: Modifier = Modifier
) {
    val preset       = ColorPresets.get(config.colorPreset)
    val previewCount = if (pinnedApps.isEmpty()) 4 else pinnedApps.size

    Canvas(modifier = modifier) {
        drawIntoCanvas { cc ->
            val nc = cc.nativeCanvas
            val pw = size.width
            val ph = size.height
            val cx = pw / 2f
            val cy = ph / 2f

            val bRadius = config.buttonRadiusPx
            val nRadius = config.nodeRadiusPx * config.revolverNodeScale
            val rRadius = bRadius * config.revolverRingRatio
            val needed  = rRadius + nRadius * 2.5f + 20f
            val avail   = min(pw, ph) / 2f * 0.82f
            val scale   = (avail / needed).coerceIn(0.25f, 2.5f)

            val sBR = bRadius * scale
            val sNR = nRadius * scale
            val sRR = rRadius * scale

            val selAngleDeg = config.selectorPosition.angleDeg
            val selAngleRad = Math.toRadians(selAngleDeg.toDouble()).toFloat()

            val arcPerItem = if (previewCount <= 1) 0f
                else minOf(60f * config.revolverArcSpacing, 240f / previewCount.toFloat())

            // ── Arc guide ────────────────────────────────────────────────────
            if (previewCount >= 2) {
                val guidePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    style       = AndroidPaint.Style.STROKE
                    strokeWidth = 1.5f * scale.coerceAtLeast(0.6f)
                    color       = previewArgb(0xFFFFFFFF.toInt(), 0.13f)
                    strokeCap   = android.graphics.Paint.Cap.ROUND
                }
                val totalArc = minOf(arcPerItem * previewCount, 240f)
                val arcStart = selAngleDeg - totalArc / 2f
                val arcRect  = RectF(cx - sRR, cy - sRR, cx + sRR, cy + sRR)
                nc.drawArc(arcRect, arcStart, totalArc, false, guidePaint)
            }

            // ── Center ball ──────────────────────────────────────────────────
            val ballPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = previewArgb(preset.buttonColor, config.ballAlpha)
            }
            nc.drawCircle(cx, cy, sBR, ballPaint)

            // Ball highlight
            val hlPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy - sBR * 0.22f, sBR * 0.62f,
                    intArrayOf(
                        previewArgb(0xFFFFFFFF.toInt(), 0.18f * config.ballAlpha),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            nc.drawCircle(cx, cy - sBR * 0.22f, sBR * 0.62f, hlPaint)

            // ── Items (arc layout, cyclic) ────────────────────────────────────
            val halfCount = previewCount / 2f
            val nodePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
            val iconPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

            for (i in 0 until previewCount) {
                var relIdx = i.toFloat()
                while (relIdx >  halfCount) relIdx -= previewCount
                while (relIdx < -halfCount) relIdx += previewCount

                val itemAngleDeg = selAngleDeg - relIdx * arcPerItem
                val itemAngleRad = Math.toRadians(itemAngleDeg.toDouble()).toFloat()
                val itemX        = cx + cos(itemAngleRad) * sRR
                val itemY        = cy + sin(itemAngleRad) * sRR

                val arcProx    = (1f - abs(relIdx) / halfCount.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val extraDim   = if (previewCount <= 2) 1f else 0.12f + 0.88f * arcProx
                val isSelected = (i == 0)
                val dimFactor  = (if (isSelected) 1.0f else 0.65f) * extraDim
                val drawRadius = if (isSelected) sNR * 1.18f
                    else sNR * (0.68f + 0.24f * arcProx).coerceIn(0.68f, 0.92f)

                if (isSelected) {
                    val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            itemX, itemY, drawRadius * 2.4f,
                            intArrayOf(
                                previewArgb(preset.nodeSelectedColor, 0.30f),
                                android.graphics.Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                        )
                    }
                    nc.drawCircle(itemX, itemY, drawRadius * 2.4f, glowPaint)
                }

                nodePaint.color = previewArgb(
                    if (isSelected) preset.nodeSelectedColor else preset.nodeIdleColor,
                    dimFactor
                )
                nc.drawCircle(itemX, itemY, drawRadius, nodePaint)

                if (pinnedApps.isNotEmpty()) {
                    val bm = pinnedApps.getOrNull(i)?.packageName
                        ?.let { LauncherRepository.iconBitmaps[it] }
                    if (bm != null) {
                        val is2 = drawRadius * 1.42f
                        iconPaint.alpha = (dimFactor * 255).toInt().coerceIn(0, 255)
                        nc.drawBitmap(
                            bm, null,
                            RectF(itemX - is2 / 2f, itemY - is2 / 2f,
                                  itemX + is2 / 2f, itemY + is2 / 2f),
                            iconPaint
                        )
                    }
                }
            }

            // ── Pointer — matches drawPinnedRevolver exactly ─────────────────
            // Tip placed ptrOffset outside the selected item; arms open outward.
            val selItemX      = cx + cos(selAngleRad) * sRR
            val selItemY      = cy + sin(selAngleRad) * sRR
            val selDrawRadius = sNR * 1.18f
            val ptrOffset     = selDrawRadius + 6f * scale.coerceAtLeast(0.6f)

            val tipX = selItemX + cos(selAngleRad) * ptrOffset
            val tipY = selItemY + sin(selAngleRad) * ptrOffset

            val armLen  = sNR * 0.72f
            val armHalf = sNR * 0.54f
            val perpCos = -sin(selAngleRad)
            val perpSin =  cos(selAngleRad)

            val arm1X = tipX + cos(selAngleRad) * armLen + perpCos * armHalf
            val arm1Y = tipY + sin(selAngleRad) * armLen + perpSin * armHalf
            val arm2X = tipX + cos(selAngleRad) * armLen - perpCos * armHalf
            val arm2Y = tipY + sin(selAngleRad) * armLen - perpSin * armHalf

            val ptrPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style       = AndroidPaint.Style.STROKE
                strokeWidth = 2.5f * scale.coerceAtLeast(0.6f)
                strokeCap   = android.graphics.Paint.Cap.ROUND
                color       = previewArgb(preset.nodeSelectedColor, 0.88f)
            }
            nc.drawLine(tipX, tipY, arm1X, arm1Y, ptrPaint)
            nc.drawLine(tipX, tipY, arm2X, arm2Y, ptrPaint)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SliderSettingCard — slim track (2dp) + small thumb (14dp), full touch area
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SliderSettingCard(
    label: String,
    hint: String = "",
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, CardBorderCol)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth()
            ) {
                Text(
                    text     = label,
                    color    = TextColor,
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                PlusMinusButton("−") {
                    onValueChange((value - step).coerceIn(range.start, range.endInclusive))
                }
                Spacer(Modifier.width(4.dp))
                DragNumberField(
                    value         = value,
                    range         = range,
                    step          = step,
                    format        = format,
                    onValueChange = onValueChange
                )
                Spacer(Modifier.width(4.dp))
                PlusMinusButton("+") {
                    onValueChange((value + step).coerceIn(range.start, range.endInclusive))
                }
            }
            if (hint.isNotEmpty()) {
                Text(
                    text     = hint,
                    color    = HintColor,
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Slim slider: 14dp visual thumb, 2dp track height
            Slider(
                value         = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValueChange,
                valueRange    = range,
                modifier      = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors        = SliderDefaults.colors(
                    thumbColor         = AccentColor,
                    activeTrackColor   = AccentColor,
                    inactiveTrackColor = Color(0xFF1E2A3A)
                ),
                thumb = {
                    Box(
                        modifier         = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Spacer(
                            Modifier
                                .size(14.dp)
                                .background(AccentColor, CircleShape)
                        )
                    }
                },
                track = { state ->
                    val fraction = if (state.valueRange.endInclusive > state.valueRange.start) {
                        ((state.value - state.valueRange.start) /
                         (state.valueRange.endInclusive - state.valueRange.start))
                            .coerceIn(0f, 1f)
                    } else 0f
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1E2A3A), RoundedCornerShape(1.dp))
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(AccentColor, RoundedCornerShape(1.dp))
                        )
                    }
                }
            )
        }
    }
}

// ─── DragNumberField ──────────────────────────────────────────────────────────
// Tap → text edit; horizontal drag → value change

@Composable
private fun DragNumberField(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    var isEditing       by remember { mutableStateOf(false) }
    var editText        by remember { mutableStateOf(format(value)) }
    var lastHapticValue by remember { mutableFloatStateOf(value) }
    var capturedStart   by remember { mutableFloatStateOf(0f) }
    var accDrag         by remember { mutableFloatStateOf(0f) }
    val haptic          = LocalHapticFeedback.current
    val focusRequester  = remember { FocusRequester() }
    val currentValue    by rememberUpdatedState(value)

    LaunchedEffect(value) {
        if (!isEditing) editText = format(value)
    }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            editText = format(currentValue)
            focusRequester.requestFocus()
        }
    }

    if (isEditing) {
        BasicTextField(
            value           = editText,
            onValueChange   = { editText = it },
            singleLine      = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction    = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                val parsed = editText.toFloatOrNull()?.coerceIn(range.start, range.endInclusive)
                if (parsed != null) onValueChange(parsed)
                isEditing = false
            }),
            textStyle = TextStyle(
                color     = TextColor,
                textAlign = TextAlign.Center,
                fontSize  = 13.sp
            ),
            modifier = Modifier
                .width(72.dp)
                .background(Color(0xFF0C1018), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { fs ->
                    if (!fs.isFocused && isEditing) {
                        val parsed = editText.toFloatOrNull()?.coerceIn(range.start, range.endInclusive)
                        if (parsed != null) onValueChange(parsed)
                        isEditing = false
                    }
                }
        )
    } else {
        Box(
            modifier = Modifier
                .width(72.dp)
                .background(Color(0xFF1A2030), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { isEditing = true })
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        accDrag += delta
                        val newVal = (capturedStart + accDrag / 22f * step)
                            .coerceIn(range.start, range.endInclusive)
                        onValueChange(newVal)
                        if (abs(newVal - lastHapticValue) >= step * 4f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastHapticValue = newVal
                        }
                    },
                    onDragStarted = {
                        capturedStart   = currentValue
                        accDrag         = 0f
                        lastHapticValue = currentValue
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = format(value),
                color      = AccentColor,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
        }
    }
}

// ─── PlusMinusButton ─────────────────────────────────────────────────────────

@Composable
private fun PlusMinusButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E2A3A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

// ─── SectionHeader ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text          = title,
        color         = AccentColor,
        style         = MaterialTheme.typography.labelMedium,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier      = Modifier.padding(top = 4.dp, bottom = 6.dp)
    )
}

// ─── SelectorPositionCard ─────────────────────────────────────────────────────

@Composable
private fun SelectorPositionCard(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, CardBorderCol)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text  = stringResource(R.string.hint_revolver_selector_position),
                color = HintColor,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                SelectorPosition.entries.forEach { pos ->
                    val isSel = config.selectorPosition == pos
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFF1E3A4A) else Color(0xFF1A2030))
                            .border(
                                width = if (isSel) 2.dp else 1.dp,
                                color = if (isSel) AccentColor else Color(0xFF2A3545),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onConfigChange(config.copy(selectorPosition = pos)) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text       = pos.displayName,
                            color      = if (isSel) AccentColor else HintColor,
                            style      = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FeatureList — grouped list with subtitles (replaces grid)
// ═══════════════════════════════════════════════════════════════════════════════

private data class FeatureItem(
    val label: String,
    val subtitle: String,
    val icon: String,
    val dialog: FeatureDialog
)

private data class FeatureGroup(val title: String, val items: List<FeatureItem>)

private val featureGroups = listOf(
    FeatureGroup(
        title = "アプリ管理",
        items = listOf(
            FeatureItem("固定アプリ",  "Revolver に表示するアプリを最大8件登録",        "★", FeatureDialog.PINNED_APPS),
            FeatureItem("非表示アプリ", "特定アプリ起動中に Pully を隠す",              "◎", FeatureDialog.HIDDEN_APPS),
        )
    ),
    FeatureGroup(
        title = "表示と動作",
        items = listOf(
            FeatureItem("配色テーマ",  "ボールとノードのカラーを変更",                  "●", FeatureDialog.COLOR),
            FeatureItem("撮影モード",  "スクリーン録画中 Pully を完全非表示",           "▶", FeatureDialog.CAPTURE_MODE),
        )
    ),
    FeatureGroup(
        title = "その他",
        items = listOf(
            FeatureItem("使用履歴",   "最近使ったアプリを優先表示（権限が必要）",        "↑", FeatureDialog.USAGE_HISTORY),
            FeatureItem("権限と起動", "オーバーレイ権限の確認と Pully の起動・停止",    "⚙", FeatureDialog.FLOATING),
        )
    ),
)

@Composable
private fun FeatureList(onOpenDialog: (FeatureDialog) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        featureGroups.forEach { group ->
            Text(
                text          = group.title,
                color         = HintColor,
                style         = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                modifier      = Modifier.padding(start = 2.dp, top = 4.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(containerColor = CardColor),
                border   = BorderStroke(1.dp, CardBorderCol)
            ) {
                Column {
                    group.items.forEachIndexed { idx, item ->
                        if (idx > 0) {
                            HorizontalDivider(
                                color    = CardBorderCol,
                                modifier = Modifier.padding(start = 50.dp)
                            )
                        }
                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDialog(item.dialog) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text      = item.icon,
                                color     = AccentColor,
                                fontSize  = 16.sp,
                                modifier  = Modifier.width(24.dp),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text       = item.label,
                                    color      = TextColor,
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text     = item.subtitle,
                                    color    = HintColor,
                                    style    = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                            Text(
                                text  = "›",
                                color = HintColor.copy(alpha = 0.5f),
                                fontSize = 20.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Feature dialog contents
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PinnedAppsDialogContent(
    localPinned: List<AppEntry>,
    onPinnedChange: (List<AppEntry>) -> Unit,
    allApps: List<AppEntry>,
    onDismiss: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var pickerSlot by remember { mutableIntStateOf(0) }

    if (showPicker) {
        AppPickerDialog(
            allApps     = allApps,
            excluded    = localPinned.filterIndexed { i, _ -> i != pickerSlot }
                .map { it.packageName }.toSet(),
            multiSelect = false,
            onSelect    = { app ->
                val list = localPinned.toMutableList()
                if (pickerSlot < list.size) list[pickerSlot] = app else list.add(app)
                onPinnedChange(list.take(LauncherRepository.MAX_PINS))
                showPicker = false
            },
            onDismiss   = { showPicker = false }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .background(Color(0xFF141923), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text       = stringResource(R.string.section_pinned_apps, LauncherRepository.MAX_PINS),
                color      = AccentColor,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text     = stringResource(R.string.pinned_apps_description),
                color    = HintColor,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Column(modifier = Modifier.weight(1f, fill = false)) {
                localPinned.forEachIndexed { i, app ->
                    PinnedAppRow(
                        index       = i,
                        app         = app,
                        canMoveUp   = i > 0,
                        canMoveDown = i < localPinned.size - 1,
                        onMoveUp    = {
                            val list = localPinned.toMutableList()
                            val tmp = list[i]; list[i] = list[i - 1]; list[i - 1] = tmp
                            onPinnedChange(list)
                        },
                        onMoveDown  = {
                            val list = localPinned.toMutableList()
                            val tmp = list[i]; list[i] = list[i + 1]; list[i + 1] = tmp
                            onPinnedChange(list)
                        },
                        onDelete    = {
                            onPinnedChange(localPinned.toMutableList().also { it.removeAt(i) })
                        },
                        onSelect    = { pickerSlot = i; showPicker = true }
                    )
                }
            }

            if (localPinned.size < LauncherRepository.MAX_PINS) {
                TextButton(
                    onClick  = { pickerSlot = localPinned.size; showPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.add_pinned_app), color = AccentColor)
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), color = HintColor)
            }
        }
    }
}

// Hidden apps: opens multi-select AppPickerDialog directly
@Composable
private fun HiddenAppsDialogContent(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    allApps: List<AppEntry>,
    onDismiss: () -> Unit
) {
    AppPickerDialog(
        allApps         = allApps,
        initialSelected = config.hiddenPackages.toSet(),
        multiSelect     = true,
        onConfirm       = { selected ->
            onConfigChange(config.copy(hiddenPackages = selected.toList()))
            onDismiss()
        },
        onDismiss       = onDismiss
    )
}

@Composable
private fun ColorDialogContent(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141923), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text       = stringResource(R.string.section_color),
                color      = AccentColor,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text     = stringResource(R.string.color_section_description),
                color    = HintColor,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorPresets.all.forEachIndexed { idx, preset ->
                    val isSel = config.colorPreset == idx
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(preset.buttonColor))
                            .border(
                                width = if (isSel) 3.dp else 1.dp,
                                color = if (isSel) Color.White else Color(0xFF4C566A),
                                shape = CircleShape
                            )
                            .clickable { onConfigChange(config.copy(colorPreset = idx)) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSel) {
                            Text("✓", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(ColorPresets.get(config.colorPreset).name, color = TextColor)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), color = HintColor)
            }
        }
    }
}

@Composable
private fun CaptureModeDialogContent(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141923), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text       = stringResource(R.string.section_capture_mode),
                color      = AccentColor,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text     = stringResource(R.string.hint_capture_mode),
                color    = HintColor,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = stringResource(R.string.setting_capture_mode),
                    color    = TextColor,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked         = config.captureMode,
                    onCheckedChange = { onConfigChange(config.copy(captureMode = it)) }
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), color = HintColor)
            }
        }
    }
}

@Composable
private fun UsageHistoryDialogContent(context: Context, onDismiss: () -> Unit) {
    val hasPerm = UsageHistoryRepository.hasPermission(context)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141923), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text       = stringResource(R.string.section_usage_history),
                color      = AccentColor,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (hasPerm) {
                Text(
                    stringResource(R.string.usage_history_enabled),
                    color = AccentColor,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    stringResource(R.string.usage_history_disabled),
                    color = HintColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), color = HintColor)
            }
        }
    }
}

@Composable
private fun FloatingDialogContent(
    hasOverlayPermission: Boolean,
    isOverlayRunning: Boolean,
    onRequestPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141923), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text       = stringResource(R.string.section_floating),
                color      = AccentColor,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            if (!hasOverlayPermission) {
                Text(
                    stringResource(R.string.overlay_permission_required),
                    color = DangerColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onRequestPermission(); onDismiss() }) {
                    Text(stringResource(R.string.grant_overlay_permission))
                }
            } else {
                Text(
                    text  = if (isOverlayRunning) stringResource(R.string.floating_status_running)
                            else stringResource(R.string.floating_status_stopped),
                    color = if (isOverlayRunning) AccentColor else HintColor
                )
                Spacer(Modifier.height(8.dp))
                if (isOverlayRunning) {
                    OutlinedButton(onClick = { onStopOverlay(); onDismiss() }) {
                        Text(stringResource(R.string.stop_floating), color = DangerColor)
                    }
                } else {
                    Button(onClick = { onStartOverlay(); onDismiss() }) {
                        Text(stringResource(R.string.start_floating))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), color = HintColor)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Reused components
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PinnedAppRow(
    index: Int,
    app: AppEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1A2535))
        ) {
            val iconBmp = LauncherRepository.iconBitmaps[app.packageName]
            if (iconBmp != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { cc ->
                        cc.nativeCanvas.drawBitmap(
                            iconBmp, null,
                            RectF(0f, 0f, size.width, size.height),
                            null
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text     = "${index + 1}. ${app.label}",
            color    = TextColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style    = MaterialTheme.typography.bodyMedium
        )
        if (canMoveUp) {
            TextButton(onClick = onMoveUp, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("↑", color = HintColor)
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
        if (canMoveDown) {
            TextButton(onClick = onMoveDown, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("↓", color = HintColor)
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
        TextButton(onClick = onSelect, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(stringResource(R.string.change), color = AccentColor)
        }
        TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(stringResource(R.string.remove_app), color = DangerColor)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// AppPickerDialog — icons + search + single/multi select
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AppPickerDialog(
    allApps: List<AppEntry>,
    excluded: Set<String> = emptySet(),
    initialSelected: Set<String> = emptySet(),
    multiSelect: Boolean = false,
    onSelect: (AppEntry) -> Unit = {},
    onConfirm: (Set<String>) -> Unit = {},
    onDismiss: () -> Unit
) {
    var query    by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelected) }

    val filteredList = remember(allApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allApps
        else allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(Color(0xFF141923), RoundedCornerShape(16.dp))
        ) {
            // ── Title + selection count ────────────────────────────────────
            Row(
                modifier          = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text       = stringResource(R.string.picker_title),
                    color      = AccentColor,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f)
                )
                if (multiSelect && selected.isNotEmpty()) {
                    Text(
                        text  = "${selected.size}件選択中",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ── Search field ───────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A2535))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text  = "アプリ名で検索...",
                            color = HintColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    BasicTextField(
                        value           = query,
                        onValueChange   = { query = it },
                        singleLine      = true,
                        textStyle       = TextStyle(color = TextColor, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier        = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(HintColor.copy(alpha = 0.3f))
                            .clickable { query = "" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text      = "×",
                            color     = TextColor,
                            fontSize  = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF1A2535))

            // ── App list ───────────────────────────────────────────────────
            if (allApps.isEmpty()) {
                Text(
                    text     = stringResource(R.string.loading),
                    color    = HintColor,
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredList, key = { it.packageName }) { app ->
                        val isExcluded = app.packageName in excluded
                        val isChecked  = app.packageName in selected

                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isExcluded) {
                                    if (multiSelect) {
                                        selected = if (isChecked) selected - app.packageName
                                                   else           selected + app.packageName
                                    } else {
                                        if (!isExcluded) onSelect(app)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // App icon
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1A2535))
                            ) {
                                val iconBmp = LauncherRepository.iconBitmaps[app.packageName]
                                if (iconBmp != null) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawIntoCanvas { cc ->
                                            cc.nativeCanvas.drawBitmap(
                                                iconBmp, null,
                                                RectF(0f, 0f, size.width, size.height),
                                                null
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text     = app.label,
                                color    = if (isExcluded) Color(0xFF4C566A) else TextColor,
                                style    = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                multiSelect -> Checkbox(
                                    checked         = isChecked,
                                    onCheckedChange = {
                                        selected = if (isChecked) selected - app.packageName
                                                   else           selected + app.packageName
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor   = AccentColor,
                                        uncheckedColor = HintColor.copy(alpha = 0.45f)
                                    )
                                )
                                isExcluded -> Text(
                                    text  = "使用中",
                                    color = Color(0xFF4C566A),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1A2535))

            // ── Bottom actions ─────────────────────────────────────────────
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = HintColor)
                }
                if (multiSelect) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selected) },
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = AccentColor.copy(alpha = 0.18f)
                        )
                    ) {
                        Text("完了", color = AccentColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─── previewArgb ─────────────────────────────────────────────────────────────

private fun previewArgb(baseColor: Int, alphaF: Float): Int {
    val a = (alphaF * 255).toInt().coerceIn(0, 255)
    return android.graphics.Color.argb(
        a,
        android.graphics.Color.red(baseColor),
        android.graphics.Color.green(baseColor),
        android.graphics.Color.blue(baseColor)
    )
}
