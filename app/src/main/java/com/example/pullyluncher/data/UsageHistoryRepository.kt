package com.example.pullyluncher.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.example.pullyluncher.model.AppEntry

/**
 * 最近使ったアプリ一覧を返すリポジトリ。
 *
 * PACKAGE_USAGE_STATS 権限が付与されていれば使用頻度順、
 * 未付与であればラベルのアルファベット順で返す。
 */
object UsageHistoryRepository {

    /**
     * インストール済みランチャーアプリを最近使った順（または ABC 順）で返す。
     * IO スレッドで呼ぶこと。
     */
    fun getRecentApps(context: Context): List<AppEntry> {
        val all = AppRepository.getInstalledLauncherApps(context)
        if (!hasPermission(context)) return all
        return try {
            val usm  = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now  = System.currentTimeMillis()
            val past = now - 1000L * 60 * 60 * 24 * 30   // 30日分
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, past, now)
                ?: return all
            val lastUsed = stats
                .filter { it.packageName != context.packageName && it.lastTimeUsed > 0L }
                .groupBy { it.packageName }
                .mapValues { (_, list) -> list.maxOf { it.lastTimeUsed } }
            all.sortedByDescending { lastUsed[it.packageName] ?: 0L }
        } catch (_: Exception) { all }
    }

    /**
     * 既存の AppEntry リストを最近使った順に並び替えて返す。
     * PackageManager は呼ばず UsageStatsManager だけを使うため高速。
     * IO スレッドで呼ぶこと。
     */
    fun reorderByUsage(context: Context, apps: List<AppEntry>): List<AppEntry> {
        if (!hasPermission(context)) return apps
        return try {
            val usm  = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now  = System.currentTimeMillis()
            val past = now - 1000L * 60 * 60 * 24 * 30
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, past, now)
                ?: return apps
            val lastUsed = stats
                .filter { it.packageName != context.packageName && it.lastTimeUsed > 0L }
                .groupBy { it.packageName }
                .mapValues { (_, list) -> list.maxOf { it.lastTimeUsed } }
            apps.sortedByDescending { lastUsed[it.packageName] ?: 0L }
        } catch (_: Exception) { apps }
    }

    /** PACKAGE_USAGE_STATS 権限が付与されているか確認する。 */
    fun hasPermission(context: Context): Boolean = try {
        val ops  = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}
