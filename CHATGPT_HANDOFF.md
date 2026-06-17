# CHATGPT_HANDOFF.md

最終更新: 2026-06-17

---

## 今回の目的

Mac版（commit 712d16e / origin/recovery/mac-latest-20260617）で実装されていた機能を、現行 main（7d78a93）へ移植する。
作業ブランチ: `integration/mac-features-20260617`

Mac版はパッケージ `com.example.pullyluncher`、現行 main は `com.rebirthkreate.pullylauncher` のため cherry-pick・merge 禁止。
必要な機能のみコードを読んで再実装した。

---

## 完了したこと

### 実装した5つの機能（各コミット済み）

| コミット | 内容 |
|---|---|
| `deac048` | feat: restore overlay position persistence |
| `5914e26` | fix: persist complete launcher UI configuration |
| `40e0588` | fix: restore safe overlay touch and hit testing behavior |
| `810081b` | feat: restore localized settings resources |
| `793bb07` | feat: restore independent overlay fade transitions |

加えて `2574694 docs: revise Mac feature migration plan` でマイグレーション計画書を作成した。

### ビルド・Lint 確認済み

```
.\gradlew.bat assembleDebug --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat lintDebug --no-daemon
Lint errors: 0, Lint warnings: 0
```

### 禁止パターン検索 (全てヒットなし)

```
com.example.pullyluncher  → 0件
AccessibilityService      → 0件
temporaryHideSeconds      → 0件
```

### push 済み

```
git push -u origin integration/mac-features-20260617
```

PR 作成用 URL: https://github.com/rebirth-kreate/PullyLauncher/pull/new/integration/mac-features-20260617

---

## 現在の問題

特になし。ビルド・Lint ともに正常。
実機・エミュレーター上での動作確認は未実施。

---

## 変更したファイル

| ファイル | 変更種別 |
|---|---|
| `MAC_FEATURE_MIGRATION_PLAN.md` | 新規作成（計画書） |
| `app/src/main/java/com/rebirthkreate/pullylauncher/data/OverlayPositionPrefs.kt` | 新規作成 |
| `app/src/main/java/com/rebirthkreate/pullylauncher/data/UiConfigPrefs.kt` | 拡張（8キー追加） |
| `app/src/main/java/com/rebirthkreate/pullylauncher/LauncherRepository.kt` | バグ修正（getForegroundPackage呼び出し削除） |
| `app/src/main/java/com/rebirthkreate/pullylauncher/OverlayTouchView.kt` | バグ修正（HIT_MARGIN, width * 0.5f） |
| `app/src/main/java/com/rebirthkreate/pullylauncher/OverlayService.kt` | 機能追加（position保存・フェード・FLAG_NOT_TOUCH_MODAL・roundToInt） |
| `app/src/main/res/values/strings.xml` | 追加（〜30キー・app_nameタイポ修正） |
| `app/src/main/res/values-en/strings.xml` | 追加（同上・英語） |
| `app/src/main/java/com/rebirthkreate/pullylauncher/ui/theme/SettingsScreen.kt` | stringResource()化 |

---

## 変更内容

### 1. OverlayPositionPrefs（新規）

SharedPreferences `"overlay_position"` に center_x / center_y を保存・復元。
- `save()`: 非有限値（NaN/Inf）は保存しない
- `load()`: 復元値が非有限の場合はデフォルト値を使用、スクリーン境界でcoerceIn

### 2. UiConfigPrefs 拡張

既存キー（nodeCount, colorPreset, ballAlpha, secureOverlay）を保持したまま、
スライダー設定8項目を追加:

```
button_radius_px, node_radius_px, spacing_px, base_offset_px,
lock_distance_px, cancel_ratio_threshold, edge_darkness, background_glow
```

各復元値はSettingsScreenのスライダー範囲と同じ値でcoerceIn。
HiddenAppsPrefs は別 prefs のまま維持（分離構造を変更しない）。

### 3. LauncherRepository バグ修正

`refreshHistory()` 内の `getForegroundPackage()` 呼び出しを削除。

**バグの詳細**: タッチダウン時に `refreshHistoryAsync()` が呼ばれ、そこで
`currentForegroundPackage = UsageHistoryRepository.getForegroundPackage(context)` が実行されていた。
UsageStats は5〜30秒程度の遅延があるため、`startForegroundPolling()` が正確に保持していた
フォアグラウンドパッケージ値を古い値で上書きしていた。
現在 `currentForegroundPackage` の書き込み元は `startForegroundPolling()` のみ。

### 4. OverlayTouchView バグ修正

```kotlin
// 変更前
val lx = event.x - width / 2
val hitR = currentVisualRadius()

// 変更後
val lx = event.x - width * 0.5f       // 整数除算誤差の排除
val hitR = currentVisualRadius() - HIT_MARGIN  // Window端のtoInt()切り捨て誤差吸収
```

`HIT_MARGIN = 2f`: タッチWindowの座標がWindowManagerのtoInt()で切り捨てられるため、
正方形Window角へのヒット誤判定を防ぐ。

### 5. OverlayService 変更

**a) FLAG_NOT_TOUCH_MODAL 追加**

```kotlin
tParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL  // ← 追加
```

ボール外へのタッチが背景アプリに届かない問題の修正。

**b) roundToInt() 統一**

WindowManager に渡す全座標・サイズで `toInt()` → `roundToInt()` に変更。
一方向への誤差蓄積を防ぐ。

**c) 位置保存・復元**

```kotlin
// onCreate: 復元
val (cx, cy) = OverlayPositionPrefs.load(this, defaultX, defaultY, ballRadius, screenW, screenH)

// onPositionChanged: 保存
OverlayPositionPrefs.save(applicationContext, cx, cy)
```

**d) フェードアニメーション**

```kotlin
private fun hideOverlay() {
    drawView.animate().alpha(0f).setDuration(FADE_DURATION_MS)
        .withEndAction { drawView.visibility = View.INVISIBLE }
        .start()
}

private fun showOverlay() {
    if (drawView.visibility != View.VISIBLE) {
        drawView.alpha = 0f
        drawView.visibility = View.VISIBLE
    }
    drawView.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
}
```

`applyBallVisibility()` がこれらを呼ぶ。ダブルタップ機能とは切り離されており、
非表示アプリ判定による表示切替時のみ動作する。

### 6. strings.xml 追加・修正

- `app_name`: PullyLuncher → PullyLauncher（両ファイル）
- 〜30キー追加（セクション見出し・設定ラベル・設定ヒント・固定アプリ・使用履歴・フローティング・非表示アプリ・アプリピッカー）
- `values/` = 日本語デフォルト、`values-en/` = 英語オーバーライド（Macと逆構造に対応）
- SettingsScreen.kt: 全ハードコード日本語文字列を `stringResource()` で置き換え

---

## 実行結果

```
git log -8 --oneline
793bb07 feat: restore independent overlay fade transitions
810081b feat: restore localized settings resources
40e0588 fix: restore safe overlay touch and hit testing behavior
5914e26 fix: persist complete launcher UI configuration
deac048 feat: restore overlay position persistence
2574694 docs: revise Mac feature migration plan
7d78a93 docs: update CHATGPT_HANDOFF for startup fix with emulator verification
4277b26 fix: resolve remaining startup process termination
```

ブランチ: `integration/mac-features-20260617`（origin にpush済み）
main へのマージは未実施・未予定。

---

## 延期した機能（実装しない）

以下は Mac版に存在するが、現行のジェスチャー設計と競合するため実装しなかった:

| 機能 | 理由 |
|---|---|
| ダブルタップ一時非表示 | 新ジェスチャー設計（タップ=ホーム）と競合。シングルタップ応答遅延が発生する |
| `temporaryHideSeconds` | 上記に付随する設定 |
| ダブルタップ検出（singleTapRunnable） | 同上 |
| 使用履歴を主要アプリ選択源にする | 新設計ではユーザー登録固定アプリから選択。長押し=ARK将来版 |

詳細は `MAC_FEATURE_MIGRATION_PLAN.md` の DEFERRED セクションを参照。

---

## ChatGPTに相談したいこと

1. **実機動作確認** — エミュレーターでのビルド確認は完了。Galaxy実機でのオーバーレイ表示・フェード・位置保存の動作確認を行いたい
2. **main へのマージ方針** — `integration/mac-features-20260617` を main へ取り込む際のタイミングと方法（PR vs direct merge）
3. **固定アプリUI** — 現状 `appSlots` は固定アプリ登録を `UiConfigPrefs` に依存しているが、SettingsScreen の「固定アプリを追加」UI実装が次のステップになる
4. **長押し=ARK** — 将来のARK版統合に向けた設計指針（`onLongPressTimer()` は現在ボール移動に使われているため再整理が必要）
