package com.rebirthkreate.pullylauncher.data

import android.content.Context
import com.rebirthkreate.pullylauncher.model.LauncherUiConfig

/**
 * LauncherUiConfig をまるごと SharedPreferences に永続化する。
 *
 * hiddenPackages だけは HiddenAppsPrefs（別ファイル）に保存する。
 * クラウドバックアップから非表示アプリ一覧を除外するため。
 *
 * 既存キー（node_count / color_preset / ball_alpha / secure_overlay）は変更しない。
 * 新規キーは保存値がない場合に LauncherUiConfig のデフォルト値へフォールバックするため
 * 既存インストールとの後方互換性を維持する。
 */
object UiConfigPrefs {

    private const val PREFS_NAME             = "ui_config"

    // ── 既存キー（変更禁止）────────────────────────────────────────
    private const val KEY_NODE_COUNT         = "node_count"
    private const val KEY_COLOR_PRESET       = "color_preset"
    private const val KEY_BALL_ALPHA         = "ball_alpha"
    private const val KEY_SECURE_OVERLAY     = "secure_overlay"

    // ── 追加キー（SettingsScreen から変更可能な全スライダー項目）──
    private const val KEY_BUTTON_RADIUS      = "button_radius_px"
    private const val KEY_NODE_RADIUS        = "node_radius_px"
    private const val KEY_SPACING            = "spacing_px"
    private const val KEY_BASE_OFFSET        = "base_offset_px"
    private const val KEY_LOCK_DISTANCE      = "lock_distance_px"
    private const val KEY_CANCEL_RATIO       = "cancel_ratio_threshold"
    private const val KEY_EDGE_DARKNESS      = "edge_darkness"
    private const val KEY_BACKGROUND_GLOW    = "background_glow"

    fun save(context: Context, config: LauncherUiConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            // 既存キー
            .putInt(KEY_NODE_COUNT,           config.nodeCount)
            .putInt(KEY_COLOR_PRESET,         config.colorPreset)
            .putFloat(KEY_BALL_ALPHA,         config.ballAlpha)
            .putBoolean(KEY_SECURE_OVERLAY,   config.secureOverlay)
            // 追加キー
            .putFloat(KEY_BUTTON_RADIUS,      config.buttonRadiusPx)
            .putFloat(KEY_NODE_RADIUS,        config.nodeRadiusPx)
            .putFloat(KEY_SPACING,            config.spacingPx)
            .putFloat(KEY_BASE_OFFSET,        config.baseOffsetPx)
            .putFloat(KEY_LOCK_DISTANCE,      config.lockDistancePx)
            .putFloat(KEY_CANCEL_RATIO,       config.cancelRatioThreshold)
            .putFloat(KEY_EDGE_DARKNESS,      config.edgeDarkness)
            .putFloat(KEY_BACKGROUND_GLOW,    config.backgroundGlow)
            .apply()
        HiddenAppsPrefs.save(context, config.hiddenPackages)
    }

    fun load(context: Context): LauncherUiConfig {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = LauncherUiConfig()
        return default.copy(
            // 既存キー
            nodeCount            = prefs.getInt(KEY_NODE_COUNT,        default.nodeCount),
            colorPreset          = prefs.getInt(KEY_COLOR_PRESET,      0),
            ballAlpha            = prefs.getFloat(KEY_BALL_ALPHA,      default.ballAlpha),
            secureOverlay        = prefs.getBoolean(KEY_SECURE_OVERLAY, default.secureOverlay),
            // 追加キー（保存値なし → LauncherUiConfig のデフォルト値）
            buttonRadiusPx       = prefs.getFloat(KEY_BUTTON_RADIUS,   default.buttonRadiusPx)
                                       .coerceIn(40f, 140f),
            nodeRadiusPx         = prefs.getFloat(KEY_NODE_RADIUS,     default.nodeRadiusPx)
                                       .coerceIn(20f, 80f),
            spacingPx            = prefs.getFloat(KEY_SPACING,         default.spacingPx)
                                       .coerceIn(80f, 220f),
            baseOffsetPx         = prefs.getFloat(KEY_BASE_OFFSET,     default.baseOffsetPx)
                                       .coerceIn(100f, 280f),
            lockDistancePx       = prefs.getFloat(KEY_LOCK_DISTANCE,   default.lockDistancePx)
                                       .coerceIn(60f, 220f),
            cancelRatioThreshold = prefs.getFloat(KEY_CANCEL_RATIO,    default.cancelRatioThreshold)
                                       .coerceIn(0.15f, 0.80f),
            edgeDarkness         = prefs.getFloat(KEY_EDGE_DARKNESS,   default.edgeDarkness)
                                       .coerceIn(0.0f, 0.9f),
            backgroundGlow       = prefs.getFloat(KEY_BACKGROUND_GLOW, default.backgroundGlow)
                                       .coerceIn(0.0f, 0.6f),
            // hiddenPackages は HiddenAppsPrefs で管理
            hiddenPackages       = HiddenAppsPrefs.load(context),
        )
    }
}
