package com.example.pullyluncher.data

import android.content.Context

/**
 * フローティングボールの画面上の位置（中心座標）を永続化する。
 *
 * ・SharedPreferences を使うため、アプリ終了・端末再起動・アップデート後も保持される。
 * ・UiConfigPrefs と別ファイルにすることで責務を明確に分離する。
 * ・保存は位置確定時（指を離したとき）のみ行い、移動中は保存しない。
 */
object OverlayPositionPrefs {

    private const val PREFS_NAME   = "overlay_position"
    private const val KEY_CENTER_X = "center_x"
    private const val KEY_CENTER_Y = "center_y"

    /** ボール中心座標を保存する。 */
    fun save(context: Context, x: Float, y: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CENTER_X, x)
            .putFloat(KEY_CENTER_Y, y)
            .apply()
    }

    /**
     * 保存済みボール中心座標を返す。
     * 未保存の場合は [defaultX] / [defaultY] をそのまま返す。
     */
    fun load(context: Context, defaultX: Float, defaultY: Float): Pair<Float, Float> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(
            prefs.getFloat(KEY_CENTER_X, defaultX),
            prefs.getFloat(KEY_CENTER_Y, defaultY)
        )
    }
}
