package com.rebirthkreate.pullylauncher.model

import android.graphics.Color

/**
 * ボール・ブロブ・ノードの配色をまとめたプリセット。
 *
 * Android Canvas（View）側は Int（ARGB）をそのまま使用。
 * Compose 側は Color(int) で変換して使用。
 * 将来カラーコード入力に対応する場合も、このクラスのフィールドを拡張するだけでよい。
 */
data class AppColorPreset(
    val name: String,
    /** ボール / ブロブ塗り色 */
    val buttonColor: Int,
    /** ブロブ輪郭色 */
    val blobStrokeColor: Int,
    /** 未選択ノード背景色 */
    val nodeIdleColor: Int,
    /** 未選択ノード中心ドット色 */
    val nodeIdleCoreColor: Int,
    /** 選択中ノード背景色 */
    val nodeSelectedColor: Int,
    /** 選択中ノード中心ドット色 */
    val nodeSelectedCoreColor: Int,
)

object ColorPresets {

    val all: List<AppColorPreset> = listOf(
        AppColorPreset(
            name                 = "ウォームレッド",
            buttonColor          = Color.parseColor("#BF616A"),
            blobStrokeColor      = Color.parseColor("#7A3040"),
            nodeIdleColor        = Color.parseColor("#3B4252"),
            nodeIdleCoreColor    = Color.parseColor("#81A1C1"),
            nodeSelectedColor    = Color.parseColor("#88C0D0"),
            nodeSelectedCoreColor= Color.WHITE,
        ),
        AppColorPreset(
            name                 = "オーシャン",
            buttonColor          = Color.parseColor("#5E81AC"),
            blobStrokeColor      = Color.parseColor("#2E4070"),
            nodeIdleColor        = Color.parseColor("#3B4252"),
            nodeIdleCoreColor    = Color.parseColor("#81A1C1"),
            nodeSelectedColor    = Color.parseColor("#88C0D0"),
            nodeSelectedCoreColor= Color.WHITE,
        ),
        AppColorPreset(
            name                 = "フォレスト",
            buttonColor          = Color.parseColor("#A3BE8C"),
            blobStrokeColor      = Color.parseColor("#4A6B3A"),
            nodeIdleColor        = Color.parseColor("#2E3440"),
            nodeIdleCoreColor    = Color.parseColor("#81A1C1"),
            nodeSelectedColor    = Color.parseColor("#B8D4A8"),
            nodeSelectedCoreColor= Color.WHITE,
        ),
        AppColorPreset(
            name                 = "サンセット",
            buttonColor          = Color.parseColor("#D08770"),
            blobStrokeColor      = Color.parseColor("#7A4030"),
            nodeIdleColor        = Color.parseColor("#3B4252"),
            nodeIdleCoreColor    = Color.parseColor("#EBCB8B"),
            nodeSelectedColor    = Color.parseColor("#EBCB8B"),
            nodeSelectedCoreColor= Color.WHITE,
        ),
        AppColorPreset(
            name                 = "ミッドナイト",
            buttonColor          = Color.parseColor("#B48EAD"),
            blobStrokeColor      = Color.parseColor("#5E3A5E"),
            nodeIdleColor        = Color.parseColor("#3B4252"),
            nodeIdleCoreColor    = Color.parseColor("#81A1C1"),
            nodeSelectedColor    = Color.parseColor("#C9B4D0"),
            nodeSelectedCoreColor= Color.WHITE,
        ),
    )

    /** インデックスが範囲外の場合は先頭プリセットを返す */
    fun get(index: Int): AppColorPreset = all.getOrElse(index) { all[0] }
}
