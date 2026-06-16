# PullyLauncher — ロードマップ

最終更新: 2026-06-16

---

## 完了済み

### v1〜v2
- フローティングボール + ブロブ + ノード UI
- アクセシビリティサービスによるフォアグラウンド検知
- 非表示アプリ設定（hiddenPackages）
- 固定アプリ（pinnedApps 最大3件）
- 使用履歴順表示（UsageStatsManager）
- カラープリセット / スライダー設定画面
- Windows / Mac 両対応の Gradle 構成（AGP 8.10.1）

### 2026-06-16
- 非表示アプリ問題の修正（Galaxy 等でも確実に除外）
  - `getForegroundPackage()` を `queryEvents(MOVE_TO_FOREGROUND)` で精度向上
  - `OverlayService` にポーリングフォールバック追加（1.5秒間隔）
- スクリーンショット非表示（`FLAG_SECURE` を両 Window に適用）

---

## 検討中 / 今後やること

### バグ修正

| 優先度 | 内容 | 状態 |
|---|---|---|
| 高 | Galaxy 等での非表示アプリ動作確認（実機テスト） | 要テスト |
| 中 | ForegroundAppService が停止時のポーリング動作確認 | 要テスト |

### 機能追加

| 優先度 | 内容 | メモ |
|---|---|---|
| 高 | FLAG_SECURE のオン/オフ設定（スクリーンショット非表示トグル） | 現在は常時ON |
| 中 | ノード数をドラッグ距離で動的に増やす | LauncherUiConfig にコメントあり |
| 中 | カラーコード直接入力 | 現在はプリセットのみ |
| 低 | テーマカラー 5色プリセット以外への対応 | |
| 低 | pinnedApps 上限を 3件以上に拡張 | MAX_PINS = 3 |

### インフラ

| 優先度 | 内容 |
|---|---|
| 中 | compileSdk / targetSdk を 36 に戻す（API 36 安定化後） |
| 低 | Compose BOM を 2024.09.00 より新しいバージョンへ更新 |

---

## 設計メモ

### オーバーレイ 2ウィンドウ構成
- `drawView`（全画面・FLAG_NOT_TOUCHABLE）: 描画専用
- `touchView`（ボールサイズ・タッチ可能）: タッチイベント受付専用
- 両 Window に `FLAG_SECURE` を設定 → スクリーンショット / 録画に映らない

### フォアグラウンド検知の優先順位
1. `ForegroundAppService`（アクセシビリティサービス）が有効なら即時検知
2. 無効 or Galaxy に強制停止された場合 → `OverlayService` のポーリングが 1.5 秒ごとに補完
3. UsageStats 権限もない場合は非表示機能が動作しない（設定画面で権限誘導）

### 非表示判定ロジック
```
hiddenPackages（設定画面で追加したパッケージ名リスト）
  ∩ currentForegroundPackage
  → INVISIBLE
```
パッケージ名の完全一致で判定。Galaxy の Samsung 系パッケージは hiddenPackages に入れた場合のみ対象。
