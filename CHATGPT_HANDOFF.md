# PullyLauncher-V1-Windows — ChatGPT Handoff

> Branch: `fix/v1-hidden-apps-secure-overlay`
> HEAD: `22edc98` (pushed to origin)
> Base: `origin/recovery/mac-latest-20260617`
> Date: 2026-06-17
> Status: **BUILD SUCCESSFUL, LINT CLEAN — Galaxy device testing pending**

---

## 1. Project Overview

PullyLauncher is a floating launcher for Android. It draws a draggable ball overlay (via `WindowManager.TYPE_APPLICATION_OVERLAY`) that the user can pull to reveal a radial app menu. Written entirely in Kotlin; uses Jetpack Compose for Settings screens, custom `Canvas`-based Views for the overlay drawing.

**Package:** `com.example.pullyluncher`
**Min SDK:** 26 (Oreo) | **Target/Compile SDK:** 36 | **AGP:** 9.0.0 | **Kotlin:** 2.2.10

> AGP 9.0+ uses built-in Kotlin. Do NOT add `org.jetbrains.kotlin.android` — it is absent by design.

Build/lint commands:
```powershell
$env:JAVA_HOME = "C:\Users\Ryon\AppData\Local\Programs\Android Studio\jbr"
.\gradlew.bat clean assembleDebug --no-daemon
.\gradlew.bat lintDebug --no-daemon
```

---

## 2. Full Commit Log (This Branch)

```
22edc98 feat: revolver-specific settings, X-axis speed, chevron pointer, arc guide, settings preview  ← HEAD
601e6c2 feat: refine V2 revolver UX — speed, snap, arc layout, double-tap-hold move
979fb91 docs: update handoff with V2 revolver architecture and test checklist
f24e487 feat: implement V2 revolver menu with long-press pinned apps ring
14e84af feat: add 3-second capture mode delay and make it session-only
bb59544 feat: replace secure capture blocking with capture mode
d554874 fix: restore reliable overlay touch and visibility state
f081e36 fix: limit secure overlay to launcher drawing bounds
80e2a43 fix: correct usage event foreground tracking
dfe1c92 feat: exclude overlay from screenshots and recordings
c8efe1f fix: replace accessibility foreground detection with usage events
712d16e backup: preserve latest Mac PullyLauncher version  ← base
```

---

## 3. ジェスチャー状態機械（完全版）

```
ACTION_DOWN
  ├─ 2回目タップ（DOUBLE_TAP_MS=300ms 以内）
  │    → singleTapRunnable キャンセル
  │    → doubleTapHoldRunnable 開始（DOUBLE_TAP_HOLD_MS=250ms）
  │    → ACTION_UP（250ms 未満で離す）  → 一時非表示（onDoubleTapTemporaryHide）
  │    → ACTION_UP（250ms 以上ホールド）→ MOVING（本体移動）＋振動
  │
  └─ 1回目タップ（または DOUBLE_TAP_MS 超過後）
       → longPressRunnable 開始（LONG_PRESS_MS=400ms）
       ├─ MOVE_CANCEL_PX=28px 超移動前（400ms 未満）
       │    → startDragMode → DRAGGING（V1 弧メニュー）
       ├─ 400ms 経過 + pinnedApps.isNotEmpty()
       │    → PINNED_MENU（V2 リボルバーメニュー）
       ├─ 400ms 経過 + pinnedApps.isEmpty()
       │    → IDLE + 振動（移動モードへは入らない）
       └─ ACTION_UP（400ms 未満・移動なし）
            → lastTapUpTime 記録 → singleTapRunnable(300ms後 goHome)

PINNED_MENU
  Move:  applyRevolverMove → rotoOffset 更新 + スナップ抵抗
  Up:    startRevolverSnap → 120ms ease-out → finishRevolverMenu → 起動 or キャンセル
```

---

## 4. V2 リボルバーメニュー詳細

### 4-1. 速度判定（22edc98 で変更）

```kotlin
// 旧: sqrt(dx²+dy²) で速度判定 → 上下移動で速度が変化してしまう問題
// 新: Pully中心からの横方向距離のみで速度を決定

val horizontalDist = abs(rawX - centerX)
val normalizedHorizDist = (horizontalDist / REVOLVER_SPEED_MAX_DIST_PX).coerceIn(0f, 1f)
val speedFactor = (REVOLVER_SPEED_NEAR + (REVOLVER_SPEED_FAR - REVOLVER_SPEED_NEAR) * normalizedHorizDist) * cfg.revolverSpeedScale

// 上下移動量 → 回転入力（変わらず）
// Pully からの横距離 → 速度制御（NEW）
// キャンセルゾーン判定は従来通り sqrt(dx²+dy²) を使用
```

定数:
- `REVOLVER_SPEED_NEAR = 2.4f` (scale=1.0 時のベース近距離速度)
- `REVOLVER_SPEED_FAR  = 0.3f` (scale=1.0 時のベース遠距離速度)
- `REVOLVER_SPEED_MAX_DIST_PX = 300f` (横方向 FAR 判定距離)

旧定数 `REVOLVER_SPEED_MULTIPLIER = 0.6f` は削除（`cfg.revolverSpeedScale` に置換）。

### 4-2. 山括弧ポインター（円形選択枠の代替）

選択位置のさらに外側に V 字形のポインターを 2 本線で描画:

```kotlin
val ptrOffset = selectedNodeRadius + 6f        // アイコン端から 6px 外
val tipX = selX + cos(selectorAngleRad) * ptrOffset
val tipY = selY + sin(selectorAngleRad) * ptrOffset
val armLen  = revolverNodeRadius * 0.72f
val armHalf = revolverNodeRadius * 0.54f
val perpCos = -sin(selectorAngleRad)
val perpSin =  cos(selectorAngleRad)
// arm1/arm2 を計算して2本の線を描画。strokeCap = ROUND
```

| SelectorPosition | ポインター向き | イメージ |
|-----------------|-------------|---------|
| RIGHT | 先端が左（アイコンを指す） | 〈 |
| LEFT  | 先端が右 | 〉 |
| TOP   | 先端が下 | ∨ |
| BOTTOM | 先端が上 | ∧ |

### 4-3. アークガイド

```kotlin
val totalArcDeg = minOf(arcPerItem * count, REVOLVER_ARC_MAX_TOTAL_DEG)
val arcStartAngle = selectorAngleDeg - totalArcDeg / 2f
canvas.drawArc(arcRect, arcStartAngle, totalArcDeg, false, arcGuidePaint)
// strokeWidth=1.5f, alpha=0.14f (通常) / 0.07f (キャンセル中)
// count >= 2 のとき描画
```

### 4-4. アーク配置

```kotlin
val arcPerItem = if (count <= 1) 0f
    else minOf(REVOLVER_ARC_PER_ITEM_BASE_DEG * cfg.revolverArcSpacing,
               REVOLVER_ARC_MAX_TOTAL_DEG / count.toFloat())
// REVOLVER_ARC_PER_ITEM_BASE_DEG = 60f
// cfg.revolverArcSpacing [0.4, 1.8]
```

### 4-5. 奥行き表現

```kotlin
val arcProx  = (1f - abs(relIdx) / halfCount.coerceAtLeast(1f)).coerceIn(0f, 1f)
val extraDim = if (count <= 2) 1f else 0.12f + 0.88f * arcProx
val drawRadius = if (isSelected) revolverNodeRadius * 1.18f
    else revolverNodeRadius * (0.68f + 0.24f * arcProx).coerceIn(0.68f, 0.92f)
```

### 4-6. PINNED_MENU 状態詳細

```
Enter:
  isPinnedMenuOpen=true / rotoOffset=0 / selectedPinnedIndex=0 / inCancelZone=false

Move (applyRevolverMove):
  キャンセルゾーン: sqrt(dx²+dy²) < buttonRadiusPx * 1.2f
  速度: (NEAR + (FAR-NEAR) * normalizedHorizDist) * revolverSpeedScale
  スナップ抵抗: distFromSnap < SNAP_ZONE_FRAC(0.28) で抵抗 (0.35強度)
  rotoOffset = ((rotoOffset + deltaOffset) % count + count) % count

Up (startRevolverSnap):
  120ms ease-out quadratic → finishRevolverMenu
```

---

## 5. V2 リボルバー専用設定値

Pull 側の設定（nodeRadiusPx, spacingPx 等）には一切影響しない。

| フィールド | 型 | デフォルト | 範囲 | SharedPreferences キー |
|-----------|-----|---------|------|----------------------|
| `revolverRingRatio` | Float | 2.4f | 1.5〜4.0 | `revolver_ring_ratio` |
| `revolverSpeedScale` | Float | 1.0f | 0.5〜2.0 | `revolver_speed_scale` |
| `revolverNodeScale` | Float | 1.0f | 0.5〜1.8 | `revolver_node_scale` |
| `revolverArcSpacing` | Float | 1.0f | 0.4〜1.8 | `revolver_arc_spacing` |

デフォルト値は `601e6c2` 実装時の見た目と操作感を維持するよう設定されている。

---

## 6. 設定画面の構成（22edc98 以降）

```
[リボルバープレビュー]  ← 展開状態を即時反映で表示

[Pull メニュー]
  メインボールサイズ / アプリアイコンサイズ / アプリ間隔 / ボール透明度
  最初のアプリ距離 / 引っ張り開始距離 / キャンセルしやすさ / 表示アプリ数 / 一時非表示秒数

[リボルバーメニュー]
  リボルバーの直径 / 回転速度 (n%) / アイコンサイズ / アプリ間隔 / 選択位置

[カラー] / [外観] / [固定アプリ] / [非表示アプリ] / [撮影モード] / [使用履歴] / [フローティング]
```

**プレビューの実装:**
- `@Composable RevolverPreview` in `SettingsScreen.kt`
- `androidx.compose.foundation.Canvas` + `drawIntoCanvas { canvas.nativeCanvas }` でネイティブ Android Canvas API
- `config` 変更 → Compose recomposition → 即時再描画
- スケール: `avail = min(pw,ph)/2 * 0.84`, `scale = avail / (rRadius + nRadius*2.5 + 20)`
- 固定アプリ登録済みの場合 `LauncherRepository.iconBitmaps` から実アイコン表示
- 固定アプリなしの場合 4 個のダミーノード

---

## 7. V1 Pull 機能への影響

今回の変更で Pull 機能は**一切変更なし**:
- `nodeRadiusPx`, `spacingPx` は Pull 側の描画のみに使われる
- `OverlayTouchView` の DRAGGING ステートマシン: 変更なし
- `OverlayExpandView` の `drawNode`, `drawBlob`, `revealProgress`: 変更なし

---

## 8. 全定数一覧

### OverlayTouchView.kt

| 定数 | 値 | 説明 |
|------|-----|------|
| `LONG_PRESS_MS` | `400L` | 長押し認識時間 |
| `DOUBLE_TAP_MS` | `300L` | ダブルタップ窓 |
| `DOUBLE_TAP_HOLD_MS` | `250L` | → 移動モード移行時間 |
| `MOVE_CANCEL_PX` | `28` | 長押しキャンセル移動距離 |
| `REVOLVER_CANCEL_ZONE_RATIO` | `1.2f` | キャンセルゾーン = ボール半径 × この値 |
| `REVOLVER_SPEED_NEAR` | `2.4f` | 横距離=0 時の基準速度 |
| `REVOLVER_SPEED_FAR` | `0.3f` | 横距離=MAX_DIST 時の基準速度 |
| `REVOLVER_SPEED_MAX_DIST_PX` | `300f` | FAR 速度になる横方向距離 (px) |
| `REVOLVER_BASE_PX_PER_ITEM` | `100f` | 1アイテム移動の基準距離 (px) |
| `REVOLVER_SNAP_ZONE_FRAC` | `0.28f` | スナップ抵抗範囲 |
| `REVOLVER_SNAP_STRENGTH` | `0.35f` | スナップ抵抗強度 |
| `REVOLVER_SNAP_DURATION_MS` | `120L` | スナップアニメーション時間 |

### OverlayExpandView.kt

| 定数 | 値 | 説明 |
|------|-----|------|
| `REVOLVER_ARC_PER_ITEM_BASE_DEG` | `60f` | アーク間隔基準（× revolverArcSpacing） |
| `REVOLVER_ARC_MAX_TOTAL_DEG` | `240f` | アーク最大合計角度 |

---

## 9. 撮影モード

- `captureMode` は **セッション限定** — SharedPreferences に保存しない
- ON → 3秒後に完全非表示。通知「撮影モード中」＋「Pullyを再表示」
- FLAG_SECURE は**一切使用しない**

---

## 10. フォアグラウンド検出（UsageEvents）

750ms ポーリング。MOVE_TO_FOREGROUND (type=1) のみ。**変更禁止**。

---

## 11. ビルド結果

```
22edc98 (V2 設定・描画改良)  — assembleDebug SUCCESSFUL 11s,  lintDebug CLEAN 20s
601e6c2 (V2 UX 調整)         — assembleDebug SUCCESSFUL 8s,   lintDebug CLEAN 15s
f24e487 (V2 実装)             — assembleDebug SUCCESSFUL 19s,  lintDebug CLEAN 24s
```

---

## 12. 禁止事項

- `git reset --hard` / `git clean` — 禁止
- Force push — 禁止
- ブランチ変更 — 禁止
- applicationId / namespace / package 変更 — 禁止
- UsageEvents フォアグラウンド検出の全面書き換え — 禁止
- AccessibilityService の再導入 — 禁止
- 撮影モードを FLAG_SECURE に戻す — 禁止
- V1 Pull 設定へリボルバー設定を混在させる — 禁止
- V2 リボルバー実装の全面削除・置換（明示的承認なし） — 禁止
- Gradle ローカル改変の破棄（AGP 9.0.0 / Gradle 9.1.0） — 禁止
- 実機未確認項目を PASS と報告する — 禁止

---

## 13. Galaxy 実機で確認が必要な項目（全項目 未確認）

### 基本動作
- [ ] タップ → ホーム / Pull → V1 弧メニュー / ダブルタップ → 一時非表示
- [ ] 非表示アプリ前面 → Pully 消える / ホーム復帰 → Pully 再表示

### V2 リボルバー
- [ ] 長押し 400ms → リボルバー表示
- [ ] 速度: Pully と同じ横位置で速い / 横に離れると遅い
- [ ] 上下移動中に横位置が変わらなければ速度がほぼ変化しない
- [ ] スナップ抵抗 + 指離し後のスナップアニメーション 120ms
- [ ] **円形選択枠がない**。山括弧ポインターが表示される
- [ ] 選択位置 RIGHT → `〈` / LEFT → `〉` / TOP → `∨` / BOTTOM → `∧`
- [ ] 薄いアークガイドが軌道に沿って表示される
- [ ] 選択中アイコンが拡大・グロー・アプリ名表示
- [ ] 端アイコンが滑らかにフェードする

### リボルバー専用設定
- [ ] 直径スライダー → リング半径が変化 (1.5〜4.0)
- [ ] 速度スライダー → 回転速度が変化 (50%〜200%)
- [ ] アイコンサイズスライダー → Pull 側と独立して変化 (0.5〜1.8)
- [ ] アプリ間隔スライダー → アーク角が変化 (0.4〜1.8)
- [ ] 選択位置変更 → 即時反映

### 設定プレビュー
- [ ] 設定画面上部にリボルバー展開状態のプレビューが表示される
- [ ] スライダー変更がプレビューに即時反映される
- [ ] 固定アプリ登録済み → 実アイコン表示 / 未登録 → ダミーノード 4 個

### ダブルタップホールド
- [ ] 2回目 250ms ホールド → 振動 + 本体移動
- [ ] 2回目すぐ離す → 一時非表示（移動にならない）

### 固定アプリ 0 個の長押し
- [ ] 振動のみ（本体移動にならない）

---

## 14. 次に行うべき作業

1. Galaxy 実機でチェックリスト確認
2. デフォルト値の実機確認後に微調整（arcSpacing=1.0=60° が広すぎ/狭すぎる場合など）
3. プレビューのスケールが端末サイズで正しく収まるか確認
