package com.example.pullyluncher.model

import android.content.Context
import android.content.Intent

/**
 * リボルバーメニューで実行できるアクションの基底インターフェース。
 *
 * 将来的に ARKAction / SystemAction / CustomAction を追加する際も
 * このインターフェースを実装するだけでリボルバー UI が利用できる。
 * 表示責務（label / iconPackage）と実行責務（execute）を分離。
 */
sealed interface LauncherAction {
    val label: String
    /** アイコン表示に使うパッケージ名。null の場合はデフォルトアイコンを使う。 */
    val iconPackage: String?
    fun execute(context: Context)
}

/** アプリ起動アクション（V2 初期実装ではこれのみ）*/
data class AppLaunchAction(
    val packageName: String,
    override val label: String,
) : LauncherAction {
    override val iconPackage: String = packageName
    override fun execute(context: Context) {
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?.let { context.startActivity(it) }
    }
}
