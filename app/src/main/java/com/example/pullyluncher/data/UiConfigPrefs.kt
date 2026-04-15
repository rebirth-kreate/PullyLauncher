package com.example.pullyluncher.data

import android.content.Context
import com.example.pullyluncher.model.LauncherUiConfig

/**
 * LauncherUiConfig をまるごと SharedPreferences に永続化する。
 */
object UiConfigPrefs {

    private const val PREFS_NAME       = "ui_config"
    private const val KEY_NODE_COUNT   = "node_count"
    private const val KEY_COLOR_PRESET = "color_preset"
    private const val KEY_BALL_ALPHA   = "ball_alpha"
    private const val KEY_HIDDEN_PKGS  = "hidden_packages"

    fun save(context: Context, config: LauncherUiConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NODE_COUNT,   config.nodeCount)
            .putInt(KEY_COLOR_PRESET, config.colorPreset)
            .putFloat(KEY_BALL_ALPHA, config.ballAlpha)
            .putString(KEY_HIDDEN_PKGS, config.hiddenPackages.joinToString(","))
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
            nodeCount      = prefs.getInt(KEY_NODE_COUNT,   default.nodeCount),
            colorPreset    = prefs.getInt(KEY_COLOR_PRESET, 0),
            ballAlpha      = prefs.getFloat(KEY_BALL_ALPHA, default.ballAlpha),
            hiddenPackages = hiddenPkgs
        )
    }
}
