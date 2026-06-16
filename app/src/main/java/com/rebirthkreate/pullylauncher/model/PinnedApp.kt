package com.rebirthkreate.pullylauncher.model

/** 固定アプリの永続化用モデル。アイコンは実行時に PackageManager からロードする。 */
data class PinnedApp(
    val packageName: String,
    val label: String
)
