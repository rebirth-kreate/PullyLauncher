# MAC_FEATURE_MIGRATION_PLAN.md

## 調査対象コミット

| 項目 | 値 |
|---|---|
| Mac 保全コミット | `712d16e` (origin/recovery/mac-latest-20260617) |
| main 基準コミット | `7d78a93` |
| 共通祖先 | `60e4e37` (Update to Version 2) |
| 作業ブランチ | `integration/mac-features-20260617` |
| 計画更新日 | 2026-06-17 |

## 構成上の前提

- Mac 旧パッケージ: `com.example.pullyluncher`
- main 現パッケージ: `com.rebirthkreate.pullylauncher`
- 移植はすべて手動で新パッケージに書き直す（cherry-pick / merge 禁止）

## 最新の操作構想（仕様）

```
タップ           → ホーム画面へ戻る
指を置いてすぐドラッグ → 固定保存アプリを選択して起動
長押し           → 将来: ARKウィールを表示（現在は移動モード）
```

アプリ選択は使用履歴中心ではなく、ユーザーが登録した固定アプリを選択する方向に転換。
長押し領域は将来 ARK 版に差し込める設計で維持する。

---

## 機能ごとの採否判定（最終版）

### MIGRATE（今回実装）

| 機能 | 対象ファイル | 理由 |
|---|---|---|
| OverlayPositionPrefs 新規作成 | `data/OverlayPositionPrefs.kt` | アプリ再起動・端末再起動後もボール位置を復元。画面外補正付きで実装 |
| UiConfigPrefs 全スライダー保存 | `data/UiConfigPrefs.kt` | 現 main は nodeCount/colorPreset/ballAlpha しか永続化していない重大な欠落 |
| FLAG_NOT_TOUCH_MODAL | `OverlayService.kt` | ボール外タッチが背後アプリへ透過しない不具合を修正 |
| roundToInt() 統一 | `OverlayService.kt` | 位置計算の切り捨て誤差を修正 |
| HIT_MARGIN ヒットテスト修正 | `OverlayTouchView.kt` | Window 端の toInt() 誤差分を吸収して角への誤タップを防ぐ |
| width * 0.5f 精度修正 | `OverlayTouchView.kt` | 整数除算誤差の排除 |
| refreshHistory バグ修正 | `LauncherRepository.kt` | 要確認後に判断（後述） |
| strings.xml 新規キー追加 | `values/strings.xml`, `values-en/strings.xml` | i18n 対応完結 |
| SettingsScreen 文字列 stringResource 化 | `ui/theme/SettingsScreen.kt` | ハードコード文字列を resource 参照に変換 |
| アプリ名スペル修正 | `values/strings.xml`, `values-en/strings.xml` | PullyLuncher → PullyLauncher |
| フェードアニメーション | `OverlayService.kt` | hideOverlay/showOverlay をダブルタップ機能から切り離して実装 |

### DEFERRED（将来検討）

| 機能 | 理由 |
|---|---|
| ダブルタップ一時非表示 | シングルタップでホームの反応を 300ms 遅らせる。タップ/ドラッグ/長押しの新操作体系と競合する可能性 |
| temporaryHideSeconds フィールド | ダブルタップ機能と一体のため同時に保留 |
| ダブルタップ待機を伴うジェスチャー判定 | 上記に依存 |

### ALREADY_PRESENT（現 main に既にある）

| 機能 | 現 main の実装 |
|---|---|
| パッケージ変更 Receiver | `OverlayService.registerPackageReceiver()` |
| ConcurrentHashMap によるアイコン競合対策 | `LauncherRepository.iconBitmaps: ConcurrentHashMap` |
| AppIconCache / HiddenAppsPrefs / PinnedAppsPrefs | `data/` 配下 |
| scheduleAppsRefresh() デバウンス | `LauncherRepository.scheduleAppsRefresh()` |
| FLAG_SECURE / applySecureFlag() | `OverlayService.applySecureFlag()` |
| POST_NOTIFICATIONS 権限ダイアログ | `MainActivity.showNotifRationale` |
| FOREGROUND_SERVICE_TYPE_SPECIAL_USE | `OverlayService.startForegroundWithNotification()` |
| BuildConfig.DEBUG ガード付きログ | 全ファイル |
| startForegroundPolling() with PowerManager | `OverlayService.startForegroundPolling()` |

### DO_NOT_MIGRATE（移植しない）

| 機能 | 理由 |
|---|---|
| MainActivity SettingsScreen 直接表示 | 現 main の PullLauncherScreen ナビゲーション構成を破壊する |
| Mac の 300ms applyBallVisibility() ループ | 現 main の startForegroundPolling() が上位互換 |
| UiConfigPrefs での hiddenPackages 直接保存 | 現 main は HiddenAppsPrefs 分離（クラウドバックアップ除外） |
| onForegroundChanged コールバック | 現 main の UsageStats ポーリング構造に存在しない |
| values-ja/ ディレクトリ | 現 main は values/ が日本語デフォルト。Mac と逆構造 |

---

## 推奨実装順序（今回のフェーズ）

```
Phase 1: data/OverlayPositionPrefs.kt（新規作成）
Phase 2: data/UiConfigPrefs.kt（スライダー設定永続化拡張）
Phase 3: LauncherRepository.kt（バグ修正。事前確認後）
Phase 4: OverlayTouchView.kt（HIT_MARGIN + 精度修正のみ）
Phase 5: OverlayService.kt（FLAG_NOT_TOUCH_MODAL + roundToInt + 位置復元 + フェード）
Phase 6: strings.xml 両ファイル + SettingsScreen.kt
```

---

## 競合リスク

| リスク | 対策 |
|---|---|
| UiConfigPrefs 新キー追加の backward compat | `default.copy()` の getFloat/getInt にデフォルト値を渡せば安全 |
| applyBallVisibility() へのフェード導入 | isTemporarilyHidden を追加しない。hiddenPackages 判定の先後に hideOverlay/showOverlay を置くだけ |
| FLAG_NOT_TOUCH_MODAL と FLAG_SECURE の組み合わせ | OR 結合は問題なし |
| roundToInt で位置がずれる | 最大 0.5px。視覚的影響なし |
| OverlayPositionPrefs で保存済み位置が画面外 | 復元時に displayMetrics でクランプ処理を追加 |
| strings.xml キー重複 | 追加前に既存キーを grep で確認 |
| app_name 変更 | values/ と values-en/ を同時に更新。applicationId は変更しない |

---

## 絶対に維持する機能

```
com.rebirthkreate.pullylauncher パッケージ
AccessibilityService 完全削除済み
UsageStats 専用化
FLAG_SECURE
POST_NOTIFICATIONS
Foreground Service specialUse
パッケージ変更 Receiver
アプリ一覧自動更新
アイコンキャッシュ
ConcurrentHashMap
起動二重ロード対策
BuildConfig.DEBUG ログ
HiddenAppsPrefs 分離
PullyStartup / PullyApps ログタグ
```
