package com.example.pullyluncher.model

/** リボルバーメニューの固定選択枠の位置。設定画面から変更可能。 */
enum class SelectorPosition {
    TOP, RIGHT, BOTTOM, LEFT;

    /** 選択枠が配置される角度（度数法、0° = 右、時計回りが正）*/
    val angleDeg: Float
        get() = when (this) {
            TOP    -> -90f
            RIGHT  ->   0f
            BOTTOM ->  90f
            LEFT   -> 180f
        }

    val displayName: String
        get() = when (this) {
            TOP    -> "Top"
            RIGHT  -> "Right"
            BOTTOM -> "Bottom"
            LEFT   -> "Left"
        }
}
