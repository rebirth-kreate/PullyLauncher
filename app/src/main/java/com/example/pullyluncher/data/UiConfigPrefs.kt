package com.example.pullyluncher.data

import android.content.Context
import com.example.pullyluncher.model.LauncherUiConfig
import com.example.pullyluncher.model.SelectorPosition

/**
 * LauncherUiConfig をまるごと SharedPreferences に永続化する。
 * アプリ終了・端末再起動・アップデート後も設定値を保持する。
 */
object UiConfigPrefs {

    private const val PREFS_NAME           = "ui_config"

    // ── 既存キー ──────────────────────────────────────────────────
    private const val KEY_NODE_COUNT       = "node_count"
    private const val KEY_COLOR_PRESET     = "color_preset"
    private const val KEY_BALL_ALPHA       = "ball_alpha"
    private const val KEY_HIDDEN_PKGS      = "hidden_packages"
    private const val KEY_TEMP_HIDE_SECS   = "temporary_hide_seconds"

    // ── 追加キー（SettingsScreen で変更可能な全項目）──────────────
    private const val KEY_BUTTON_RADIUS    = "button_radius_px"
    private const val KEY_NODE_RADIUS      = "node_radius_px"
    private const val KEY_SPACING          = "spacing_px"
    private const val KEY_BASE_OFFSET      = "base_offset_px"
    private const val KEY_LOCK_DISTANCE    = "lock_distance_px"
    private const val KEY_CANCEL_RATIO     = "cancel_ratio_threshold"
    private const val KEY_EDGE_DARKNESS    = "edge_darkness"
    private const val KEY_BACKGROUND_GLOW  = "background_glow"
    private const val KEY_SELECTOR_POSITION = "selector_position"

    fun save(context: Context, config: LauncherUiConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            // 数値スライダー
            .putFloat(KEY_BUTTON_RADIUS,   config.buttonRadiusPx)
            .putFloat(KEY_NODE_RADIUS,     config.nodeRadiusPx)
            .putFloat(KEY_SPACING,         config.spacingPx)
            .putFloat(KEY_BASE_OFFSET,     config.baseOffsetPx)
            .putFloat(KEY_LOCK_DISTANCE,   config.lockDistancePx)
            .putFloat(KEY_CANCEL_RATIO,    config.cancelRatioThreshold)
            .putFloat(KEY_EDGE_DARKNESS,   config.edgeDarkness)
            .putFloat(KEY_BACKGROUND_GLOW, config.backgroundGlow)
            .putFloat(KEY_BALL_ALPHA,      config.ballAlpha)
            // 整数・列挙
            .putInt(KEY_NODE_COUNT,        config.nodeCount)
            .putInt(KEY_COLOR_PRESET,      config.colorPreset)
            .putInt(KEY_TEMP_HIDE_SECS,    config.temporaryHideSeconds)
            // リスト
            .putString(KEY_HIDDEN_PKGS,    config.hiddenPackages.joinToString(","))
            // captureMode は永続化しない（セッション限定。再起動で自動的に false に戻る）
            .putString(KEY_SELECTOR_POSITION, config.selectorPosition.name)
            .apply()
    }

    /** 保存済み値をデフォルト設定にマージして返す */
    fun load(context: Context): LauncherUiConfig {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = LauncherUiConfig()
        val hiddenStr  = prefs.getString(KEY_HIDDEN_PKGS, "") ?: ""
        val hiddenPkgs = if (hiddenStr.isBlank()) emptyList()
                         else hiddenStr.split(",").filter { it.isNotBlank() }
        return default.copy(
            buttonRadiusPx       = prefs.getFloat(KEY_BUTTON_RADIUS,   default.buttonRadiusPx),
            nodeRadiusPx         = prefs.getFloat(KEY_NODE_RADIUS,     default.nodeRadiusPx),
            spacingPx            = prefs.getFloat(KEY_SPACING,         default.spacingPx),
            baseOffsetPx         = prefs.getFloat(KEY_BASE_OFFSET,     default.baseOffsetPx),
            lockDistancePx       = prefs.getFloat(KEY_LOCK_DISTANCE,   default.lockDistancePx),
            cancelRatioThreshold = prefs.getFloat(KEY_CANCEL_RATIO,    default.cancelRatioThreshold),
            edgeDarkness         = prefs.getFloat(KEY_EDGE_DARKNESS,   default.edgeDarkness),
            backgroundGlow       = prefs.getFloat(KEY_BACKGROUND_GLOW, default.backgroundGlow),
            ballAlpha            = prefs.getFloat(KEY_BALL_ALPHA,      default.ballAlpha),
            nodeCount            = prefs.getInt(KEY_NODE_COUNT,        default.nodeCount),
            colorPreset          = prefs.getInt(KEY_COLOR_PRESET,      0),
            temporaryHideSeconds = prefs.getInt(KEY_TEMP_HIDE_SECS,    default.temporaryHideSeconds).coerceIn(1, 10),
            hiddenPackages       = hiddenPkgs,
            // captureMode は常に false で起動（永続化しない）
            selectorPosition = try {
                SelectorPosition.valueOf(
                    prefs.getString(KEY_SELECTOR_POSITION, "RIGHT") ?: "RIGHT"
                )
            } catch (_: Exception) { SelectorPosition.RIGHT }
        )
    }
}
