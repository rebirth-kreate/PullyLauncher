package com.example.pullyluncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import com.example.pullyluncher.data.AppIconCache
import com.example.pullyluncher.data.PinnedAppsPrefs
import com.example.pullyluncher.data.UsageHistoryRepository
import com.example.pullyluncher.model.AppEntry
import com.example.pullyluncher.model.AppSlot
import com.example.pullyluncher.model.LauncherUiConfig
import com.example.pullyluncher.model.PinnedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * アプリ内とフローティングオーバーレイで共有するシングルトン。
 *
 * ・config      — 設定値。変更時に onConfigChanged コールバックが発火する。
 * ・allApps     — 全インストール済みランチャーアプリ（使用頻度順、権限なければ ABC 順）。
 * ・pinnedApps  — 固定アプリ（最大 MAX_PINS 件）。
 * ・appSlots    — 表示スロット。固定アプリ先頭、残りを履歴で埋め、重複除外、nodeCount 上限。
 * ・iconBitmaps — メモリアイコンキャッシュ（一次ソースは AppIconCache のディスクキャッシュ）。
 */
object LauncherRepository {

    // ── config（StateFlow で即時通知）───────────────────────────────

    private val _configFlow = MutableStateFlow(LauncherUiConfig())

    /** config 変更を複数コレクターへ即時配信する StateFlow */
    val configFlow: StateFlow<LauncherUiConfig> = _configFlow.asStateFlow()

    var config: LauncherUiConfig
        get() = _configFlow.value
        set(value) { _configFlow.value = value }

    /**
     * フォアグラウンドアプリが切り替わったときに呼ばれるコールバック。
     * ForegroundAppService が発火し、OverlayService がボール可視判定に使用する。
     * Service.onDestroy で null にリセットすること。
     */
    @Volatile var onForegroundChanged: (() -> Unit)? = null

    /**
     * loadAll() でアイコンのロードが完了したときに呼ばれるコールバック。
     * OverlayService が展開ビューの再描画トリガーに使用する。
     */
    @Volatile var onIconsLoaded: (() -> Unit)? = null

    // ── アプリデータ ─────────────────────────────────────────────────

    @Volatile var allApps: List<AppEntry> = emptyList()
        private set

    /**
     * 直前にフォアグラウンドにあったアプリの packageName。
     * refreshHistory / refreshHistoryAsync の呼び出し時に更新される。
     * computeSlots でスロットから除外するために使う。
     */
    @Volatile var currentForegroundPackage: String? = null

    @Volatile var pinnedApps: List<AppEntry> = emptyList()
        private set

    /**
     * メモリアイコンキャッシュ（セッション中の高速アクセス用）。
     * 一次ソースは AppIconCache のディスクキャッシュ。
     */
    val iconBitmaps: MutableMap<String, Bitmap> = mutableMapOf()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    const val MAX_PINS    = 3
    private const val ICON_PX = 96

    // ── スロット計算 ────────────────────────────────────────────────

    val appSlots: List<AppSlot>
        get() = computeSlots(config.nodeCount)

    fun computeSlots(nodeCount: Int): List<AppSlot> {
        // 現在のフォアグラウンドアプリと自アプリをスロットから除外する
        val excludePkgs   = setOfNotNull(currentForegroundPackage, "com.example.pullyluncher")
        val pinnedPkgs    = pinnedApps.map { it.packageName }.toSet()
        val filteredPinned = pinnedApps.filter { it.packageName !in excludePkgs }
        val histFill      = allApps.filter { it.packageName !in pinnedPkgs && it.packageName !in excludePkgs }
        return (filteredPinned + histFill)
            .take(nodeCount)
            .mapIndexed { idx, app -> AppSlot(index = idx, pinnedApp = app) }
    }

    // ── ロード ──────────────────────────────────────────────────────

    /** アプリ一覧が未ロードなら非同期でフルロードを開始する。 */
    fun loadAppsIfNeeded(context: Context) {
        if (allApps.isEmpty()) scope.launch { loadAll(context) }
    }

    /**
     * 使用履歴の順序を毎回再取得して allApps を更新する（PackageManager クエリなし）。
     * IO スレッドで呼ぶこと。
     */
    suspend fun refreshHistory(context: Context) {
        if (allApps.isNotEmpty()) {
            allApps = UsageHistoryRepository.reorderByUsage(context, allApps)
        }
        // currentForegroundPackage は ForegroundAppService が即時・正確に管理する。
        // UsageStatsManager は最大数十秒の遅延があるため、ここでは上書きしない。
        // 上書きすると「非表示アプリ離脱後も古い値で hide が継続する」バグが発生する。
    }

    /** refreshHistory の非 suspend ラッパー（Service など非コルーチンコンテキスト用）。 */
    fun refreshHistoryAsync(context: Context) {
        scope.launch { refreshHistory(context) }
    }

    /**
     * アプリ一覧・固定アプリ・全アイコンをロードして状態を更新する。
     * IO スレッドで呼ぶこと。
     *
     * アイコンは優先順位: メモリキャッシュ → ディスクキャッシュ → Drawable 生成＆保存。
     * 初回起動時は全アプリのアイコンを生成してディスクに保存する（多少時間がかかる）。
     * 2回目以降はディスクからの読み込みのみで高速に完了する。
     */
    suspend fun loadAll(context: Context) {
        allApps = UsageHistoryRepository.getRecentApps(context)

        val saved = PinnedAppsPrefs.load(context)
        pinnedApps = saved.mapNotNull { pinned ->
            allApps.find { it.packageName == pinned.packageName }
        }

        // 全アプリのアイコンをロード（初回：生成＆保存、2回目以降：ディスクから高速読み込み）
        allApps.forEach { loadIconIfNeeded(context, it) }
        // アイコンロード完了を通知（展開ビューの再描画トリガー）
        onIconsLoaded?.invoke()
    }

    // ── 固定アプリ更新 ──────────────────────────────────────────────

    fun setPinnedApps(context: Context, apps: List<AppEntry>) {
        pinnedApps = apps.take(MAX_PINS)
        PinnedAppsPrefs.save(
            context,
            apps.take(MAX_PINS).map { PinnedApp(it.packageName, it.label) }
        )
        scope.launch { apps.take(MAX_PINS).forEach { loadIconIfNeeded(context, it) } }
    }

    // ── アイコン読み込み ─────────────────────────────────────────────

    /**
     * アイコンをメモリキャッシュに読み込む。
     *
     * 優先順位:
     *   1. メモリキャッシュ（セッション中の高速アクセス）
     *   2. ディスクキャッシュ（filesDir/app_icons/）
     *   3. PackageManager の Drawable から生成してディスクに保存
     */
    private fun loadIconIfNeeded(context: Context, app: AppEntry) {
        if (iconBitmaps.containsKey(app.packageName)) return

        // ディスクキャッシュを試みる
        val cached = AppIconCache.load(context, app.packageName)
        if (cached != null) {
            iconBitmaps[app.packageName] = cached
            return
        }

        // Drawable から生成してメモリ＆ディスクに保存
        val drawable = app.icon ?: return
        val bm = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bm)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        iconBitmaps[app.packageName] = bm
        AppIconCache.save(context, app.packageName, bm)
    }
}
