package com.example.pullyluncher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
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

    // ACTIVITY_RESUMED = 19 (API 29+) — 生の値を使い @RequiresApi のlint警告を回避
    private const val ACTIVITY_RESUMED_INT = 19
    private val IGNORED_PACKAGES = setOf("com.android.systemui", "android")

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

    /**
     * queryEvents() で現在フォアグラウンドにあるアプリの packageName を返す。
     * 対応イベント: MOVE_TO_FOREGROUND (API 26+), ACTIVITY_RESUMED (API 29+)
     * 直近 10 秒のイベントを走査し、最も新しいフォアグラウンド遷移を返す。
     * 権限がない、またはイベントが存在しない場合は null を返す。
     */
    fun getForegroundPackageFromEvents(context: Context): String? {
        if (!hasPermission(context)) return null
        return try {
            val usm  = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now  = System.currentTimeMillis()
            val past = now - 10_000L
            val events = usm.queryEvents(past, now) ?: return null
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == context.packageName) continue
                if (event.packageName in IGNORED_PACKAGES) continue
                val isFg = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                           event.eventType == ACTIVITY_RESUMED_INT
                if (isFg && event.timeStamp > latestTime) {
                    latestTime = event.timeStamp
                    latestPkg = event.packageName
                }
            }
            latestPkg
        } catch (_: Exception) { null }
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
