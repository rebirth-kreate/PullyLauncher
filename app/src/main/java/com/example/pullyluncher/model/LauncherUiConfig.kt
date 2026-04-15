package com.example.pullyluncher.model

/**
 * ランチャー UI の設定値。
 *
 * 将来の拡張予定:
 * - pinnedApps: List<String>  — スロットごとの固定アプリ (package name)
 * - themeColors: ThemeColors  — 5色プリセット / カラーコード入力に対応
 * - dynamicNodeCount: Boolean — 引っ張り距離に応じてノード数を動的に増やす
 */
data class LauncherUiConfig(
    val buttonRadiusPx: Float = 80f,
    val nodeRadiusPx: Float = 44f,
    val lockDistancePx: Float = 120f,
    val baseOffsetPx: Float = 180f,
    val spacingPx: Float = 140f,
    val cancelRatioThreshold: Float = 0.40f,
    val minParallelForRatio: Float = 120f,
    val nodeCount: Int = 5,
    val edgeDarkness: Float = 0.42f,
    val backgroundGlow: Float = 0.20f,
    val nodeRevealBackOffsetPx: Float = 46f,
    val nodeRevealWindowRatio: Float = 0.60f,
    /** カラープリセットインデックス（0〜ColorPresets.all.lastIndex）*/
    val colorPreset: Int = 0,
    /** メインボールの不透明度（0.3〜1.0）*/
    val ballAlpha: Float = 1.0f,
    /** フローティングボールを非表示にするアプリの packageName リスト */
    val hiddenPackages: List<String> = emptyList()
)