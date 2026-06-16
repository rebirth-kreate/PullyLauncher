package com.rebirthkreate.pullylauncher.data

import android.content.Context
import com.rebirthkreate.pullylauncher.model.LauncherUiConfig

/**
 * LauncherUiConfig をまるごと SharedPreferences に永続化する。
 *
 * hiddenPackages だけは HiddenAppsPrefs（別ファイル）に保存する。
 * クラウドバックアップから非表示アプリ一覧を除外するため。
 */
object UiConfigPrefs {

    private const val PREFS_NAME         = "ui_config"
    private const val KEY_NODE_COUNT     = "node_count"
    private const val KEY_COLOR_PRESET   = "color_preset"
    private const val KEY_BALL_ALPHA     = "ball_alpha"
    private const val KEY_SECURE_OVERLAY = "secure_overlay"

    fun save(context: Context, config: LauncherUiConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NODE_COUNT,         config.nodeCount)
            .putInt(KEY_COLOR_PRESET,       config.colorPreset)
            .putFloat(KEY_BALL_ALPHA,       config.ballAlpha)
            .putBoolean(KEY_SECURE_OVERLAY, config.secureOverlay)
            .apply()
        HiddenAppsPrefs.save(context, config.hiddenPackages)
    }

    fun load(context: Context): LauncherUiConfig {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = LauncherUiConfig()
        return default.copy(
            nodeCount      = prefs.getInt(KEY_NODE_COUNT,        default.nodeCount),
            colorPreset    = prefs.getInt(KEY_COLOR_PRESET,      0),
            ballAlpha      = prefs.getFloat(KEY_BALL_ALPHA,      default.ballAlpha),
            hiddenPackages = HiddenAppsPrefs.load(context),
            secureOverlay  = prefs.getBoolean(KEY_SECURE_OVERLAY, default.secureOverlay)
        )
    }
}
