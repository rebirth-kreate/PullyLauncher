package com.example.pullyluncher.data

import android.content.Context
import com.example.pullyluncher.model.LauncherUiConfig

/**
 * nodeCount と colorPreset を SharedPreferences に永続化する。
 *
 * その他の設定値（ボタン半径、スペーシング等）はデフォルト値のままで問題ないため
 * 現時点では保存対象外とする。必要に応じて KEY_* 定数を追加して拡張できる。
 */
object UiConfigPrefs {

    private const val PREFS_NAME       = "ui_config"
    private const val KEY_NODE_COUNT   = "node_count"
    private const val KEY_COLOR_PRESET = "color_preset"

    fun save(context: Context, config: LauncherUiConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NODE_COUNT,   config.nodeCount)
            .putInt(KEY_COLOR_PRESET, config.colorPreset)
            .apply()
    }

    /** 保存済み値をデフォルト設定にマージして返す */
    fun load(context: Context): LauncherUiConfig {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = LauncherUiConfig()
        return default.copy(
            nodeCount   = prefs.getInt(KEY_NODE_COUNT,   default.nodeCount),
            colorPreset = prefs.getInt(KEY_COLOR_PRESET, 0)
        )
    }
}
