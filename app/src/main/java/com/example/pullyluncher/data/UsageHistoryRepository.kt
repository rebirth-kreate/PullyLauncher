package com.example.pullyluncher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.example.pullyluncher.model.AppEntry

/**
 * 最近使ったアプリ一覧を返すリポジトリ。
 *
 * PACKAGE_USAGE_STATS 権限が付与されていれば使用頻度順、
 * 未付与であればラベルのアルファベット順で返す。
 */
object UsageHistoryRepository {

    /**
     * System UI など一時的なレイヤーのパッケージ。
     * これらのイベントは無視して直前の前面パッケージを維持する。
     * ホーム画面 Launcher は含まない（ホームへ戻ったときに正しく検知するため）。
     */
    private val IGNORED_PACKAGES = setOf("com.android.systemui", "android")

    /** per-event の詳細ログ用タグ。`adb shell setprop log.tag.PullyEvents D` で有効化。 */
    private const val LOG_TAG = "PullyEvents"

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
     * カーソル方式でアクティビティ前面イベントを検索する。
     *
     * 対象イベント: [UsageEvents.Event.MOVE_TO_FOREGROUND] のみ（integer value = 1）。
     *
     * ── イベント定数について ──────────────────────────────────────────────────
     * [UsageEvents.Event.MOVE_TO_FOREGROUND]  API 21+  value=1  deprecated since API 29
     * [UsageEvents.Event.ACTIVITY_RESUMED]    API 29+  value=1  MOVE_TO_FOREGROUND の後継
     * [UsageEvents.Event.FOREGROUND_SERVICE_START]     value=19 (フォアグラウンドServiceの起動)
     *
     * 両者は整数値 1 を共有するため、deprecated な MOVE_TO_FOREGROUND を使っても
     * ACTIVITY_RESUMED と同じイベントを受け取れる。@Suppress("DEPRECATION") で
     * lint 警告を局所的に抑制し、minSdk=26（API 26〜28）での互換性を維持する。
     * raw integer 19 は FOREGROUND_SERVICE_START であり、Activity の前面移動ではないため使用しない。
     * ────────────────────────────────────────────────────────────────────────
     *
     * @param afterTimestampMs このタイムスタンプ（ミリ秒）より**新しい**イベントだけを対象とする（排他的下限）。
     *                         0L を渡すと [lookbackMs] 内の全イベントを検索する（初回起動用）。
     * @param lookbackMs queryEvents の検索開始点の上限（古い [afterTimestampMs] を clamp するだけ）。
     *                   [afterTimestampMs] が十分新しければ lookbackMs は実質無効。
     * @return 新しいフォアグラウンドイベントが見つかった場合は (packageName, eventTimestamp)、
     *         なければ null。呼び出し元は null のとき現在の前面パッケージを維持すること。
     */
    @Suppress("DEPRECATION")
    fun queryForegroundEvent(
        context: Context,
        afterTimestampMs: Long,
        lookbackMs: Long = 60_000L
    ): Pair<String, Long>? {
        if (!hasPermission(context)) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // afterTimestampMs の 200ms 手前から検索してタイムスタンプ精度のズレを吸収する。
            // 同一イベントの重複処理は後段の「ts <= afterTimestampMs」チェックで除外する。
            val queryStart = (afterTimestampMs - 200L).coerceAtLeast(now - lookbackMs)
            val events = usm.queryEvents(queryStart, now) ?: return null
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = afterTimestampMs   // strictly-greater check: ts > afterTimestampMs のみ採用
            val verbose = Log.isLoggable(LOG_TAG, Log.DEBUG)
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg  = event.packageName
                val type = event.eventType
                val ts   = event.timeStamp
                when {
                    pkg == context.packageName -> {
                        if (verbose) Log.d(LOG_TAG,
                            "skip type=$type pkg=$pkg ts=$ts reason=ownPkg")
                        continue
                    }
                    pkg in IGNORED_PACKAGES -> {
                        if (verbose) Log.d(LOG_TAG,
                            "skip type=$type pkg=$pkg ts=$ts reason=systemPkg")
                        continue
                    }
                    // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED == 1
                    // FOREGROUND_SERVICE_START == 19 → 対象外
                    type != UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (verbose) Log.d(LOG_TAG,
                            "skip type=$type pkg=$pkg ts=$ts reason=notForeground")
                        continue
                    }
                    ts <= afterTimestampMs -> {
                        if (verbose) Log.d(LOG_TAG,
                            "skip type=$type pkg=$pkg ts=$ts reason=alreadySeen(after=$afterTimestampMs)")
                        continue
                    }
                    else -> {
                        if (verbose) Log.d(LOG_TAG,
                            "accept type=$type pkg=$pkg ts=$ts")
                        if (ts > latestTime) {
                            latestTime = ts
                            latestPkg  = pkg
                        }
                    }
                }
            }
            if (latestPkg != null) Pair(latestPkg, latestTime) else null
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
