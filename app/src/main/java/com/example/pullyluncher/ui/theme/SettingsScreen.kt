package com.example.pullyluncher.ui.theme

import android.content.Intent
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun SettingsScreen(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    onClose: () -> Unit,
    hasOverlayPermission: Boolean  = false,
    isOverlayRunning: Boolean      = false,
    onRequestPermission: () -> Unit = {},
    onStartOverlay: () -> Unit      = {},
    onStopOverlay: () -> Unit       = {}
) {
    val context = LocalContext.current

    var localPinned      by remember { mutableStateOf(LauncherRepository.pinnedApps) }
    var showPicker       by remember { mutableStateOf(false) }
    var pickerSlot       by remember { mutableIntStateOf(0) }
    var showHiddenPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (LauncherRepository.allApps.isEmpty()) {
            withContext(Dispatchers.IO) { LauncherRepository.loadAll(context) }
            localPinned = LauncherRepository.pinnedApps
        }
    }

    if (showHiddenPicker) {
        AppPickerDialog(
            allApps  = LauncherRepository.allApps,
            excluded = config.hiddenPackages.toSet(),
            onSelect = { selectedApp ->
                onConfigChange(config.copy(hiddenPackages = config.hiddenPackages + selectedApp.packageName))
                showHiddenPicker = false
            },
            onDismiss = { showHiddenPicker = false }
        )
    }

    if (showPicker) {
        AppPickerDialog(
            allApps  = LauncherRepository.allApps,
            excluded = localPinned
                .filterIndexed { i, _ -> i != pickerSlot }
                .map { it.packageName }.toSet(),
            onSelect = { selectedApp ->
                val list = localPinned.toMutableList()
                if (pickerSlot < list.size) list[pickerSlot] = selectedApp
                else list.add(selectedApp)
                localPinned = list.take(LauncherRepository.MAX_PINS)
                LauncherRepository.setPinnedApps(context, localPinned)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F16))
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text  = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ═══════════════════════════════════════════════════════════
        // リボルバー プレビュー
        // ═══════════════════════════════════════════════════════════
        Text(
            text  = stringResource(R.string.revolver_preview_label),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF81A1C1),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        RevolverPreview(
            config     = config,
            pinnedApps = localPinned,
            modifier   = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF13181F))
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ═══════════════════════════════════════════════════════════
        // Pull メニュー設定
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_pull_menu))

        SettingSlider(
            label         = stringResource(R.string.setting_button_radius),
            hint          = stringResource(R.string.hint_button_radius),
            value         = config.buttonRadiusPx,
            range         = 40f..140f,
            onValueChange = { onConfigChange(config.copy(buttonRadiusPx = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_node_radius),
            hint          = stringResource(R.string.hint_node_radius),
            value         = config.nodeRadiusPx,
            range         = 20f..80f,
            onValueChange = { onConfigChange(config.copy(nodeRadiusPx = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_spacing),
            hint          = stringResource(R.string.hint_spacing),
            value         = config.spacingPx,
            range         = 80f..220f,
            onValueChange = { onConfigChange(config.copy(spacingPx = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_ball_alpha),
            hint          = stringResource(R.string.hint_ball_alpha),
            value         = config.ballAlpha,
            range         = 0.3f..1.0f,
            onValueChange = { onConfigChange(config.copy(ballAlpha = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_base_offset),
            hint          = stringResource(R.string.hint_base_offset),
            value         = config.baseOffsetPx,
            range         = 100f..280f,
            onValueChange = { onConfigChange(config.copy(baseOffsetPx = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_lock_distance),
            hint          = stringResource(R.string.hint_lock_distance),
            value         = config.lockDistancePx,
            range         = 60f..220f,
            onValueChange = { onConfigChange(config.copy(lockDistancePx = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_cancel_ratio),
            hint          = stringResource(R.string.hint_cancel_ratio),
            value         = config.cancelRatioThreshold,
            range         = 0.15f..0.80f,
            onValueChange = { onConfigChange(config.copy(cancelRatioThreshold = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_node_count),
            hint          = stringResource(R.string.hint_node_count),
            value         = config.nodeCount.toFloat(),
            range         = 3f..10f,
            steps         = 6,
            onValueChange = { onConfigChange(config.copy(nodeCount = it.roundToInt())) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_temporary_hide_seconds),
            hint          = stringResource(R.string.hint_temporary_hide_seconds),
            value         = config.temporaryHideSeconds.toFloat(),
            range         = 1f..10f,
            steps         = 8,
            onValueChange = { onConfigChange(config.copy(temporaryHideSeconds = it.roundToInt())) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // リボルバー設定
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_revolver))

        SettingSlider(
            label         = stringResource(R.string.setting_revolver_diameter),
            hint          = stringResource(R.string.hint_revolver_diameter),
            value         = config.revolverRingRatio,
            range         = 1.5f..4.0f,
            onValueChange = { onConfigChange(config.copy(revolverRingRatio = it)) }
        )
        SettingSlider(
            label         = "${stringResource(R.string.setting_revolver_speed)} : ${(config.revolverSpeedScale * 100).roundToInt()}%",
            hint          = stringResource(R.string.hint_revolver_speed),
            value         = config.revolverSpeedScale,
            range         = 0.5f..2.0f,
            showRaw       = true,
            onValueChange = { onConfigChange(config.copy(revolverSpeedScale = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_revolver_icon_size),
            hint          = stringResource(R.string.hint_revolver_icon_size),
            value         = config.revolverNodeScale,
            range         = 0.5f..1.8f,
            onValueChange = { onConfigChange(config.copy(revolverNodeScale = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_revolver_spacing),
            hint          = stringResource(R.string.hint_revolver_spacing),
            value         = config.revolverArcSpacing,
            range         = 0.4f..1.8f,
            onValueChange = { onConfigChange(config.copy(revolverArcSpacing = it)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text  = stringResource(R.string.hint_revolver_selector_position),
            color = Color(0xFF81A1C1),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SelectorPosition.entries.forEach { pos ->
                val isSelected = config.selectorPosition == pos
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) Color(0xFF1E3A4A) else Color(0xFF1A1F2E))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFF88C0D0) else Color(0xFF4C566A),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { onConfigChange(config.copy(selectorPosition = pos)) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = pos.displayName,
                        color = if (isSelected) Color(0xFF88C0D0) else Color(0xFF81A1C1),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // カラー
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_color))

        Text(
            text  = stringResource(R.string.color_section_description),
            color = Color(0xFF81A1C1),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ColorPresets.all.forEachIndexed { index, preset ->
                val isSelected = config.colorPreset == index
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(preset.buttonColor))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else Color(0xFF4C566A),
                            shape = CircleShape
                        )
                        .clickable { onConfigChange(config.copy(colorPreset = index)) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Text(
                            text       = "✓",
                            color      = Color.White,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text  = ColorPresets.get(config.colorPreset).name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // 外観（汎用）
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_appearance))

        SettingSlider(
            label         = stringResource(R.string.setting_edge_darkness),
            hint          = stringResource(R.string.hint_edge_darkness),
            value         = config.edgeDarkness,
            range         = 0.0f..0.9f,
            onValueChange = { onConfigChange(config.copy(edgeDarkness = it)) }
        )
        SettingSlider(
            label         = stringResource(R.string.setting_background_glow),
            hint          = stringResource(R.string.hint_background_glow),
            value         = config.backgroundGlow,
            range         = 0.0f..0.6f,
            onValueChange = { onConfigChange(config.copy(backgroundGlow = it)) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // 固定アプリ
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_pinned_apps, LauncherRepository.MAX_PINS))

        Text(
            text  = stringResource(R.string.pinned_apps_description),
            color = Color(0xFF81A1C1),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        localPinned.forEachIndexed { i, app ->
            PinnedAppRow(
                index       = i,
                app         = app,
                canMoveUp   = i > 0,
                canMoveDown = i < localPinned.size - 1,
                onMoveUp    = {
                    val list = localPinned.toMutableList()
                    val tmp = list[i]; list[i] = list[i - 1]; list[i - 1] = tmp
                    localPinned = list
                    LauncherRepository.setPinnedApps(context, localPinned)
                },
                onMoveDown  = {
                    val list = localPinned.toMutableList()
                    val tmp = list[i]; list[i] = list[i + 1]; list[i + 1] = tmp
                    localPinned = list
                    LauncherRepository.setPinnedApps(context, localPinned)
                },
                onDelete    = {
                    localPinned = localPinned.toMutableList().also { it.removeAt(i) }
                    LauncherRepository.setPinnedApps(context, localPinned)
                },
                onSelect    = {
                    pickerSlot = i
                    showPicker = true
                }
            )
        }

        if (localPinned.size < LauncherRepository.MAX_PINS) {
            TextButton(
                onClick  = {
                    pickerSlot = localPinned.size
                    showPicker = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.add_pinned_app),
                    color = Color(0xFF88C0D0)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // 非表示アプリ
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_hidden_apps))

        Text(
            text  = stringResource(R.string.hidden_apps_description),
            color = Color(0xFF81A1C1),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        config.hiddenPackages.forEach { pkg ->
            val label = LauncherRepository.allApps.find { it.packageName == pkg }?.label ?: pkg
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = label,
                    color    = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick        = {
                        onConfigChange(config.copy(hiddenPackages = config.hiddenPackages - pkg))
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text(stringResource(R.string.remove_app), color = Color(0xFFBF616A))
                }
            }
        }

        TextButton(
            onClick  = { showHiddenPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.add_hidden_app), color = Color(0xFF88C0D0))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // 撮影モード
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_capture_mode))

        Text(
            text  = stringResource(R.string.hint_capture_mode),
            color = Color(0xFF81A1C1),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text     = stringResource(R.string.setting_capture_mode),
                color    = Color.White,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked         = config.captureMode,
                onCheckedChange = { onConfigChange(config.copy(captureMode = it)) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // 使用履歴
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_usage_history))

        val hasUsageStats = UsageHistoryRepository.hasPermission(context)
        if (hasUsageStats) {
            Text(
                text  = stringResource(R.string.usage_history_enabled),
                color = Color(0xFF88C0D0),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                text  = stringResource(R.string.usage_history_disabled),
                color = Color(0xFF81A1C1),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            ) {
                Text(stringResource(R.string.grant_permission))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════════════════════════════════
        // フローティング制御
        // ═══════════════════════════════════════════════════════════
        SettingSection(title = stringResource(R.string.section_floating))

        if (!hasOverlayPermission) {
            Text(
                text  = stringResource(R.string.overlay_permission_required),
                color = Color(0xFFBF616A),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.grant_overlay_permission))
            }
        } else {
            val statusColor = if (isOverlayRunning) Color(0xFF88C0D0) else Color(0xFF81A1C1)
            Text(
                text  = if (isOverlayRunning) stringResource(R.string.floating_status_running)
                        else stringResource(R.string.floating_status_stopped),
                color = statusColor,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isOverlayRunning) {
                OutlinedButton(onClick = onStopOverlay) {
                    Text(stringResource(R.string.stop_floating), color = Color(0xFFBF616A))
                }
            } else {
                Button(onClick = onStartOverlay) {
                    Text(stringResource(R.string.start_floating))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onClose) {
            Text(stringResource(R.string.settings_close))
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

// ── リボルバープレビュー ──────────────────────────────────────────

@Composable
private fun RevolverPreview(
    config: LauncherUiConfig,
    pinnedApps: List<AppEntry>,
    modifier: Modifier = Modifier
) {
    val preset = ColorPresets.get(config.colorPreset)

    // プレビューに使うダミーアプリ数（実登録 or 4個のダミー）
    val previewCount = if (pinnedApps.isEmpty()) 4 else pinnedApps.size

    Canvas(modifier = modifier) {
        drawIntoCanvas { composeCanvas ->
            val nc = composeCanvas.nativeCanvas
            val pw = size.width
            val ph = size.height
            val cx = pw / 2f
            val cy = ph / 2f

            // 設定値をそのまま使いつつ、プレビュー領域に収まるようスケール
            val bRadius = config.buttonRadiusPx
            val nRadius = config.nodeRadiusPx * config.revolverNodeScale
            val rRadius = bRadius * config.revolverRingRatio
            val needed  = rRadius + nRadius * 2.5f + 20f
            val avail   = min(pw, ph) / 2f * 0.84f
            val scale   = (avail / needed).coerceIn(0.25f, 2.5f)

            val sBRadius = bRadius * scale
            val sNRadius = nRadius * scale
            val sRRadius = rRadius * scale

            val selectorAngleDeg = config.selectorPosition.angleDeg
            val selectorAngleRad = Math.toRadians(selectorAngleDeg.toDouble()).toFloat()

            val arcPerItem = if (previewCount <= 1) 0f
                else minOf(60f * config.revolverArcSpacing, 240f / previewCount.toFloat())

            // ── アークガイド ────────────────────────────────────────
            if (previewCount >= 2) {
                val guidePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    style      = AndroidPaint.Style.STROKE
                    strokeWidth = 1.5f * scale.coerceAtLeast(0.6f)
                    color      = previewArgb(0xFFFFFFFF.toInt(), 0.13f)
                    strokeCap  = android.graphics.Paint.Cap.ROUND
                }
                val totalArc  = minOf(arcPerItem * previewCount, 240f)
                val arcStart  = selectorAngleDeg - totalArc / 2f
                val arcRect   = RectF(cx - sRRadius, cy - sRRadius, cx + sRRadius, cy + sRRadius)
                nc.drawArc(arcRect, arcStart, totalArc, false, guidePaint)
            }

            // ── 中心ボール ──────────────────────────────────────────
            val ballPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = previewArgb(preset.buttonColor, config.ballAlpha)
            }
            nc.drawCircle(cx, cy, sBRadius, ballPaint)

            // ── アイテム ─────────────────────────────────────────────
            val halfCount = previewCount / 2f
            val nodePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
            val iconPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

            for (i in 0 until previewCount) {
                var relIdx = i.toFloat()  // rotoOffset=0 → item 0 がセレクター位置
                while (relIdx >  halfCount) relIdx -= previewCount
                while (relIdx < -halfCount) relIdx += previewCount

                val itemAngleDeg = selectorAngleDeg - relIdx * arcPerItem
                val itemAngleRad = Math.toRadians(itemAngleDeg.toDouble()).toFloat()
                val itemX = cx + cos(itemAngleRad) * sRRadius
                val itemY = cy + sin(itemAngleRad) * sRRadius

                val arcProx  = (1f - abs(relIdx) / halfCount.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val extraDim = if (previewCount <= 2) 1f else 0.12f + 0.88f * arcProx

                val isSelected = (i == 0)
                val baseDim = if (isSelected) 1.00f else 0.65f
                val dimFactor = baseDim * extraDim
                val itemAlpha = (dimFactor * 255).toInt().coerceIn(0, 255)

                val drawRadius = if (isSelected) sNRadius * 1.18f
                    else sNRadius * (0.68f + 0.24f * arcProx).coerceIn(0.68f, 0.92f)

                // 選択中グロー
                if (isSelected) {
                    val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            itemX, itemY, drawRadius * 2.4f,
                            intArrayOf(previewArgb(preset.nodeSelectedColor, 0.30f),
                                       android.graphics.Color.TRANSPARENT),
                            floatArrayOf(0f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    nc.drawCircle(itemX, itemY, drawRadius * 2.4f, glowPaint)
                }

                nodePaint.color = previewArgb(
                    if (isSelected) preset.nodeSelectedColor else preset.nodeIdleColor,
                    dimFactor
                )
                nc.drawCircle(itemX, itemY, drawRadius, nodePaint)

                // 実際のアプリアイコン（登録済みの場合）
                if (pinnedApps.isNotEmpty()) {
                    val pkg = pinnedApps.getOrNull(i)?.packageName
                    val bm  = pkg?.let { LauncherRepository.iconBitmaps[it] }
                    if (bm != null) {
                        val iconSize = drawRadius * 1.42f
                        iconPaint.alpha = itemAlpha
                        nc.drawBitmap(
                            bm, null,
                            RectF(itemX - iconSize / 2f, itemY - iconSize / 2f,
                                  itemX + iconSize / 2f, itemY + iconSize / 2f),
                            iconPaint
                        )
                    }
                }
            }

            // ── 山括弧ポインター ───────────────────────────────────
            val selX = cx + cos(selectorAngleRad) * sRRadius
            val selY = cy + sin(selectorAngleRad) * sRRadius
            val ptrOffset = sNRadius * 1.18f + 6f * scale.coerceAtLeast(0.5f)
            val tipX = selX + cos(selectorAngleRad) * ptrOffset
            val tipY = selY + sin(selectorAngleRad) * ptrOffset
            val armLen  = sNRadius * 0.72f
            val armHalf = sNRadius * 0.54f
            val perpCos = -sin(selectorAngleRad)
            val perpSin =  cos(selectorAngleRad)
            val arm1X = tipX + cos(selectorAngleRad) * armLen + perpCos * armHalf
            val arm1Y = tipY + sin(selectorAngleRad) * armLen + perpSin * armHalf
            val arm2X = tipX + cos(selectorAngleRad) * armLen - perpCos * armHalf
            val arm2Y = tipY + sin(selectorAngleRad) * armLen - perpSin * armHalf

            val chevronPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style      = AndroidPaint.Style.STROKE
                strokeWidth = 2.5f * scale.coerceAtLeast(0.6f)
                strokeCap  = android.graphics.Paint.Cap.ROUND
                color      = previewArgb(preset.nodeSelectedColor, 0.88f)
            }
            nc.drawLine(tipX, tipY, arm1X, arm1Y, chevronPaint)
            nc.drawLine(tipX, tipY, arm2X, arm2Y, chevronPaint)
        }
    }
}

/** プレビュー用 ARGB ヘルパー：alphaF [0,1] を適用したカラーを返す。 */
private fun previewArgb(baseColor: Int, alphaF: Float): Int {
    val a = (alphaF * 255).toInt().coerceIn(0, 255)
    return android.graphics.Color.argb(
        a,
        android.graphics.Color.red(baseColor),
        android.graphics.Color.green(baseColor),
        android.graphics.Color.blue(baseColor)
    )
}

// ── 固定アプリ行 ──────────────────────────────────────────────────

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
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = "${index + 1}. ${app.label}",
            color    = Color.White,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (canMoveUp) {
            TextButton(onClick = onMoveUp, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("↑", color = Color(0xFF81A1C1))
            }
        } else {
            Spacer(modifier = Modifier.width(40.dp))
        }
        if (canMoveDown) {
            TextButton(onClick = onMoveDown, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("↓", color = Color(0xFF81A1C1))
            }
        } else {
            Spacer(modifier = Modifier.width(40.dp))
        }
        TextButton(onClick = onSelect, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(stringResource(R.string.change), color = Color(0xFF88C0D0))
        }
        TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(stringResource(R.string.remove_app), color = Color(0xFFBF616A))
        }
    }
}

// ── アプリピッカーダイアログ ──────────────────────────────────────

@Composable
private fun AppPickerDialog(
    allApps: List<AppEntry>,
    excluded: Set<String>,
    onSelect: (AppEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .background(Color(0xFF1A1F2E))
                .padding(16.dp)
        ) {
            Text(
                text  = stringResource(R.string.picker_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF2E3440))
            Spacer(modifier = Modifier.height(4.dp))

            if (allApps.isEmpty()) {
                Text(
                    text     = stringResource(R.string.loading),
                    color    = Color(0xFF81A1C1),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn {
                    items(allApps, key = { it.packageName }) { app ->
                        val isExcluded = app.packageName in excluded
                        TextButton(
                            onClick  = { if (!isExcluded) onSelect(app) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled  = !isExcluded
                        ) {
                            Text(
                                text     = app.label,
                                color    = if (isExcluded) Color(0xFF4C566A) else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.cancel), color = Color(0xFF81A1C1))
            }
        }
    }
}

// ── 共通ヘルパー ─────────────────────────────────────────────────

@Composable
private fun SettingSection(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleMedium,
        color    = Color(0xFF88C0D0),
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SettingSlider(
    label: String,
    hint: String = "",
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    showRaw: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // showRaw=true のとき label にすでに値が含まれているため、値を付加しない
        val displayText = if (showRaw) label
            else if (steps > 0) "$label : ${value.roundToInt()}"
            else "$label : ${"%.2f".format(value)}"
        Text(text = displayText, color = Color.White)
        if (hint.isNotEmpty()) {
            Text(
                text  = hint,
                color = Color(0xFF81A1C1),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = range,
            steps         = steps
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}
