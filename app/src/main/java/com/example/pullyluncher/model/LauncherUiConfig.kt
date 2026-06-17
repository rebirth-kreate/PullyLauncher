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
    val hiddenPackages: List<String> = emptyList(),
    /** ダブルタップで一時非表示にする秒数（5〜10）*/
    val temporaryHideSeconds: Int = 5,
    /** 撮影モード中は Pully を完全に非表示にする（FLAG_SECURE は使わない） */
    val captureMode: Boolean = false,
    /**
     * リボルバーメニューの固定選択枠の位置。
     * デフォルト RIGHT: Pully が画面左端に配置されているため、選択枠を右側（画面内側）に向ける。
     */
    val selectorPosition: SelectorPosition = SelectorPosition.RIGHT,

    // ── V2 リボルバー専用設定（Pull 側には影響しない） ──────────────────
    /** リボルバーリング半径比率（ボール半径 × この値）。1.5〜6.0。デフォルト 2.4 = 現行と同等。 */
    val revolverRingRatio: Float = 2.4f,
    /** 回転速度スケール（1.0 = デフォルト速度の 100%）。0.5〜2.0。 */
    val revolverSpeedScale: Float = 1.0f,
    /** リボルバー内アイコンサイズ比率（nodeRadiusPx × この値）。0.5〜1.8。デフォルト 1.0 = Pull と同サイズ。 */
    val revolverNodeScale: Float = 1.0f,
    /** リボルバーアーク間隔比率（1.0 = 60°/アイテム）。0.4〜1.8。Pull 側の間隔設定とは独立。 */
    val revolverArcSpacing: Float = 1.0f
)