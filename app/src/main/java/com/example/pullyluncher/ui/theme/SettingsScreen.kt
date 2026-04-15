package com.example.pullyluncher.ui.theme

import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.pullyluncher.ForegroundAppService
import com.example.pullyluncher.LauncherRepository
import com.example.pullyluncher.R
import com.example.pullyluncher.data.UsageHistoryRepository
import com.example.pullyluncher.model.AppEntry
import com.example.pullyluncher.model.ColorPresets
import com.example.pullyluncher.model.LauncherUiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    config: LauncherUiConfig,
    onConfigChange: (LauncherUiConfig) -> Unit,
    onClose: () -> Unit,
    // ── フローティング制御（デフォルト値付きで既存呼び出しとの互換性を保つ）──
    hasOverlayPermission: Boolean  = false,
    isOverlayRunning: Boolean      = false,
    onRequestPermission: () -> Unit = {},
    onStartOverlay: () -> Unit      = {},
    onStopOverlay: () -> Unit       = {}
) {
    val context = LocalContext.current

    // ── 固定アプリ状態 ─────────────────────────────────────────────
    // SettingsScreen が開いたとき LauncherRepository.pinnedApps を読む。
    // SettingsScreen 内の変更はすぐ Repository に書き込む。
    var localPinned      by remember { mutableStateOf(LauncherRepository.pinnedApps) }
    var showPicker       by remember { mutableStateOf(false) }
    var pickerSlot       by remember { mutableIntStateOf(0) }
    var showHiddenPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // allApps がまだ未ロードなら IO スレッドでロード
        if (LauncherRepository.allApps.isEmpty()) {
            withContext(Dispatchers.IO) { LauncherRepository.loadAll(context) }
            localPinned = LauncherRepository.pinnedApps
        }
    }

    // ── 非表示アプリピッカーダイアログ ─────────────────────────────
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

    // ── 固定アプリピッカーダイアログ ────────────────────────────────
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

    // ── 本体 ────────────────────────────────────────────────────────
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

        Spacer(modifier = Modifier.height(20.dp))

        // ---- 見た目 ----
        SettingSection(title = stringResource(R.string.section_appearance))

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

        // ---- カラー ----
        SettingSection(title = "カラー")

        Text(
            text  = "ボール・ブロブ・ノードの配色を選択します。",
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

        // ---- 挙動 ----
        SettingSection(title = stringResource(R.string.section_behavior))

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

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 固定アプリ ----
        SettingSection(title = "固定アプリ（最大${LauncherRepository.MAX_PINS}件）")

        Text(
            text  = "先頭に固定表示するアプリを設定します。残りの枠は使用履歴で埋まります。",
            color = Color(0xFF81A1C1),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 固定アプリ一覧
        localPinned.forEachIndexed { i, app ->
            PinnedAppRow(
                index      = i,
                app        = app,
                canMoveUp  = i > 0,
                canMoveDown = i < localPinned.size - 1,
                onMoveUp   = {
                    val list = localPinned.toMutableList()
                    val tmp = list[i]; list[i] = list[i - 1]; list[i - 1] = tmp
                    localPinned = list
                    LauncherRepository.setPinnedApps(context, localPinned)
                },
                onMoveDown = {
                    val list = localPinned.toMutableList()
                    val tmp = list[i]; list[i] = list[i + 1]; list[i + 1] = tmp
                    localPinned = list
                    LauncherRepository.setPinnedApps(context, localPinned)
                },
                onDelete   = {
                    localPinned = localPinned.toMutableList().also { it.removeAt(i) }
                    LauncherRepository.setPinnedApps(context, localPinned)
                },
                onSelect   = {
                    pickerSlot = i
                    showPicker = true
                }
            )
        }

        // 追加ボタン（3件未満のとき）
        if (localPinned.size < LauncherRepository.MAX_PINS) {
            TextButton(
                onClick  = {
                    pickerSlot = localPinned.size
                    showPicker = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = "＋ 固定アプリを追加",
                    color = Color(0xFF88C0D0)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 使用履歴 ----
        SettingSection(title = "使用履歴")

        val hasUsageStats = UsageHistoryRepository.hasPermission(context)
        if (hasUsageStats) {
            Text(
                text  = "最近使ったアプリ順に表示します。",
                color = Color(0xFF88C0D0),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                text  = "「使用履歴へのアクセス」権限を付与すると最近使ったアプリ順に表示されます。\n未付与の場合はアルファベット順になります。",
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
                Text("権限を設定する")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- フォアグラウンド検知 ----
        SettingSection(title = stringResource(R.string.section_foreground_detection))

        val isFgServiceEnabled = isForegroundServiceEnabled(context)
        if (isFgServiceEnabled) {
            Text(
                text  = "有効（アプリ切り替えを即時検知しています）",
                color = Color(0xFF88C0D0),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                text  = "「ユーザー補助」で有効にすると、フローティングボールの非表示切替や履歴更新が即時になります。",
                color = Color(0xFF81A1C1),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            ) {
                Text("ユーザー補助設定を開く")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- フローティング ----
        SettingSection(title = "フローティング")

        if (!hasOverlayPermission) {
            Text(
                text  = "他アプリ上への表示には「他のアプリの上に重ねて表示」権限が必要です。",
                color = Color(0xFFBF616A),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestPermission) { Text("表示権限を許可する") }
        } else {
            val statusColor = if (isOverlayRunning) Color(0xFF88C0D0) else Color(0xFF81A1C1)
            val statusText  = if (isOverlayRunning) "表示中" else "停止中"
            Text(
                text  = "フローティングボタン: $statusText",
                color = statusColor,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isOverlayRunning) {
                OutlinedButton(onClick = onStopOverlay) {
                    Text("停止する", color = Color(0xFFBF616A))
                }
            } else {
                Button(onClick = onStartOverlay) { Text("フローティング開始") }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 非表示アプリ ----
        SettingSection(title = "フローティング非表示アプリ")

        Text(
            text  = "これらのアプリが前面にある場合、フローティングボールを非表示にします。",
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
                    onClick = {
                        onConfigChange(config.copy(hiddenPackages = config.hiddenPackages - pkg))
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("解除", color = Color(0xFFBF616A))
                }
            }
        }

        TextButton(
            onClick  = { showHiddenPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("＋ 非表示アプリを追加", color = Color(0xFF88C0D0))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onClose) {
            Text(stringResource(R.string.settings_close))
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
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
            Text("変更", color = Color(0xFF88C0D0))
        }
        TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("解除", color = Color(0xFFBF616A))
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
                text  = "アプリを選択",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF2E3440))
            Spacer(modifier = Modifier.height(4.dp))

            if (allApps.isEmpty()) {
                Text(
                    text     = "読み込み中...",
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
                Text("キャンセル", color = Color(0xFF81A1C1))
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
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val displayValue = if (steps > 0) value.roundToInt().toString()
                          else "%.2f".format(value)
        Text(text = "$label : $displayValue", color = Color.White)
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

// ── アクセシビリティサービス有効確認 ─────────────────────────────

/**
 * ForegroundAppService が現在有効になっているか Settings.Secure で確認する。
 * ForegroundAppService.isRunning はプロセス再起動時にリセットされるため、
 * OS の設定値を直接確認する方が信頼性が高い。
 */
private fun isForegroundServiceEnabled(context: android.content.Context): Boolean {
    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
    if (!am.isEnabled) return false
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val target = "${context.packageName}/com.example.pullyluncher.ForegroundAppService"
    return flat.split(":").any { it.equals(target, ignoreCase = true) }
}
