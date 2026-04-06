package com.example.pullyluncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * アプリアイコンをディスクに永続キャッシュする。
 *
 * 保存先: [Context.filesDir]/app_icons/{packageName}.png
 *
 * ◆ filesDir を選んだ理由
 *   cacheDir はシステムが空き容量不足時に削除するため、2回目以降も安定して読み込むために
 *   永続ストレージである filesDir を使用する。
 *   アプリアンインストール時に自動的に削除される。
 *
 * ◆ ファイル名規則
 *   {packageName}.png（ドットはファイル名として問題ない）
 *
 * ◆ アイコン更新が必要な場合
 *   該当パッケージのファイルを削除するだけで次回起動時に再生成される。
 *   現時点では自動更新は行わない（大規模リファクタ回避）。
 */
object AppIconCache {

    private const val ICONS_DIR = "app_icons"

    private fun iconFile(context: Context, packageName: String): File {
        val dir = File(context.filesDir, ICONS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$packageName.png")
    }

    /** ディスクキャッシュが存在するか確認する */
    fun exists(context: Context, packageName: String): Boolean =
        iconFile(context, packageName).exists()

    /**
     * ディスクキャッシュからアイコンを読み込む。
     * 存在しない場合は null を返す。IO スレッドで呼ぶこと。
     */
    fun load(context: Context, packageName: String): Bitmap? = try {
        val file = iconFile(context, packageName)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    } catch (_: Exception) { null }

    /**
     * アイコンを PNG 形式でディスクに保存する。
     * IO スレッドで呼ぶこと。
     */
    fun save(context: Context, packageName: String, bitmap: Bitmap) {
        try {
            FileOutputStream(iconFile(context, packageName)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {}
    }
}
