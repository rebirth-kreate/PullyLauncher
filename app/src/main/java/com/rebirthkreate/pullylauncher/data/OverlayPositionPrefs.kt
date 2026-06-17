package com.rebirthkreate.pullylauncher.data

import android.content.Context

/**
 * フローティングボールの画面上の中心座標を SharedPreferences に永続化する。
 *
 * ・保存タイミング: 指を離したとき（移動中は保存しない）
 * ・復元タイミング: OverlayService.onCreate() で保存済み座標があればデフォルト位置に優先して使用
 * ・画面外補正: 復元時に displayMetrics でクランプし、ボールが画面外に出ない
 * ・不正値ガード: Float が NaN / Infinity の場合はデフォルトへフォールバック
 */
object OverlayPositionPrefs {

    private const val PREFS_NAME   = "overlay_position"
    private const val KEY_CENTER_X = "center_x"
    private const val KEY_CENTER_Y = "center_y"

    fun save(context: Context, x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CENTER_X, x)
            .putFloat(KEY_CENTER_Y, y)
            .apply()
    }

    /**
     * 保存済み座標を返す。
     *
     * @param defaultX 保存値がない場合のデフォルト X（px）
     * @param defaultY 保存値がない場合のデフォルト Y（px）
     * @param ballRadius ボール半径（px）。画面内クランプに使用
     * @param screenW 画面幅（px）
     * @param screenH 画面高さ（px）
     */
    fun load(
        context: Context,
        defaultX: Float,
        defaultY: Float,
        ballRadius: Float,
        screenW: Float,
        screenH: Float,
    ): Pair<Float, Float> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawX = prefs.getFloat(KEY_CENTER_X, defaultX)
        val rawY = prefs.getFloat(KEY_CENTER_Y, defaultY)

        val safeX = if (rawX.isFinite()) rawX else defaultX
        val safeY = if (rawY.isFinite()) rawY else defaultY

        val clampedX = safeX.coerceIn(ballRadius, (screenW - ballRadius).coerceAtLeast(ballRadius))
        val clampedY = safeY.coerceIn(ballRadius, (screenH - ballRadius).coerceAtLeast(ballRadius))
        return Pair(clampedX, clampedY)
    }
}
