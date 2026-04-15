package com.example.pullyluncher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * TYPE_WINDOW_STATE_CHANGED イベントを監視して
 * フォアグラウンドアプリの変化を即時検知するアクセシビリティサービス。
 *
 * ── 役割 ────────────────────────────────────────────────────────
 *   ・LauncherRepository.currentForegroundPackage を更新する
 *   ・LauncherRepository.onForegroundChanged コールバックを発火させる
 *     → OverlayService がボールの可視/非可視を即時切替
 *   ・アプリ切り替え時に使用履歴を非同期で最新化する
 *
 * ── 有効化手順 ────────────────────────────────────────────────
 *   設定 → ユーザー補助 → インストール済みアプリ →
 *   PullyLuncher → 「フォアグラウンドアプリ検知」を ON
 */
class ForegroundAppService : AccessibilityService() {

    /** 直前に検知したフォアグラウンドパッケージ（同一アプリでの連続発火を防ぐ） */
    private var lastPackage: String? = null

    companion object {
        /** サービスが接続中かどうか（設定画面の表示に使用） */
        var isRunning: Boolean = false
            private set

        /** 無視するシステムパッケージ（頻繁にイベントが来るが可視制御には不要） */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // 自分自身やシステム UI は無視
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return

        // 同一アプリ内のウィンドウ遷移は無視（アクティビティ切替など）
        if (pkg == lastPackage) return
        lastPackage = pkg

        // フォアグラウンドパッケージを即時更新
        LauncherRepository.currentForegroundPackage = pkg

        // 使用履歴を非同期で最新化（IO スレッドで実行）
        LauncherRepository.refreshHistoryAsync(applicationContext)

        // OverlayService にボール可視判定を依頼
        // AccessibilityService のコールバックはメインスレッドで呼ばれる
        LauncherRepository.onForegroundChanged?.invoke()
    }

    override fun onInterrupt() {
        // 必須オーバーライド（割り込みイベントは今回は使用しない）
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        lastPackage = null
    }
}
