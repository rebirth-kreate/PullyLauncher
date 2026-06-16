package com.rebirthkreate.pullylauncher.data

import android.content.Context

/**
 * 非表示アプリのパッケージ名一覧を専用 SharedPreferences に保存する。
 *
 * ◆ 専用ファイルに分離した理由
 *   一般設定（ノード数・カラー等）と異なり、非表示アプリ一覧は端末固有の
 *   インストール済みアプリ情報を含むため、クラウドバックアップ対象外にする。
 *   backup_rules.xml / data_extraction_rules.xml で "pully_hidden_prefs" を除外する。
 */
object HiddenAppsPrefs {

    private const val PREFS_NAME = "pully_hidden_prefs"
    private const val KEY_HIDDEN = "hidden_packages"

    fun save(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HIDDEN, packages.joinToString(","))
            .apply()
    }

    fun load(context: Context): List<String> {
        val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HIDDEN, "") ?: ""
        return if (str.isBlank()) emptyList()
               else str.split(",").filter { it.isNotBlank() }
    }
}
