# PullyLauncher-V1-Windows — ChatGPT Handoff

> Branch: `fix/v1-hidden-apps-secure-overlay`
> HEAD: `e102d80` (pushed to origin)
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
6b74aa3 feat: redesign settings screen — Pull/Revolver tabs, SliderSettingCard, previews, feature grid  ← HEAD
cf9fd06 docs: update handoff for 22edc98
22edc98 feat: revolver-specific settings, X-axis speed, chevron pointer, arc guide, settings preview
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

### 4-2. 山括弧ポインター（OverlayExpandView / 設定プレビュー）

**オーバーレイ実装 (OverlayExpandView):** アイコン外側に V 字形ポインター（先端がアイコン外側に向く、シェブロンが外開き）

**設定プレビュー (SettingsScreen RevolverPreview — 本コミットで修正):** オーバーレイと同じロジックを使用するよう変更。先端はアイコン外側。

```
RIGHT 位置の場合:
  Pully → ● (選択アイコン) → < (ポインター先端)
                                 \  (腕2本が外開き)
```

設定プレビューの計算（`drawPinnedRevolver` と完全一致）:
```kotlin
val selItemX = cx + cos(selAngleRad) * sRR       // 選択アイコム中心
val selItemY = cy + sin(selAngleRad) * sRR
val selDrawRadius = sNR * 1.18f
val ptrOffset = selDrawRadius + 6f * scale        // アイコン外側のオフセット
val tipX = selItemX + cos(selAngleRad) * ptrOffset  // 先端 = アイコン外側
val tipY = selItemY + sin(selAngleRad) * ptrOffset
val armLen  = sNR * 0.72f
val armHalf = sNR * 0.54f
val perpCos = -sin(selAngleRad)
val perpSin =  cos(selAngleRad)
// arm1 / arm2 は先端から外側方向＋垂直方向に延びる → 外開きシェブロン
nc.drawLine(tipX, tipY, arm1X, arm1Y, ptrPaint)
nc.drawLine(tipX, tipY, arm2X, arm2Y, ptrPaint)
```

| SelectorPosition | OverlayExpandView ポインター | 設定プレビュー ポインター |
|-----------------|---------------------------|----------------------|
| RIGHT | アイコン右外側に 〈 (外開き) | 同じ（統一済み） |
| LEFT  | アイコン左外側に 〉 | 同じ |
| TOP   | アイコン上外側に ∨ | 同じ |
| BOTTOM | アイコン下外側に ∧ | 同じ |

> **6b74aa3 時点の実装**: 設定プレビューのポインターは Pully とアイコンの間に配置（内側）で **オーバーレイと異なっていた**。本コミットで修正済み。

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

## 6. 設定画面の構成（6b74aa3 → 本コミット — UI 改善）

ファイル: `app/src/main/java/com/example/pullyluncher/ui/theme/SettingsScreen.kt`

### 6-A. 本コミットの変更内容（e102d80 — 2026-06-17/18）

変更ファイル: `SettingsScreen.kt` のみ。V1 Pull ロジック / V2 Revolver ロジック / overlay / UiConfig に変更なし。

#### 1. プレビューの固定（スクロール外）

**変更前:** `Column(verticalScroll) { プレビュー; 設定リスト }` — プレビューがスクロールで消える  
**変更後:** `Column(fillMaxSize) { プレビュー(固定); Column(weight(1f).verticalScroll) { 設定リスト } }`

両ページとも同様のレイアウト構造に統一。

#### 2. Pull プレビュー — buildBlobPath の数式を再現

`PullPreviewCard` の描画を `OverlayExpandView.buildBlobPath` と同じベジェ曲線で実装。

```kotlin
// tipHalf = r * 0.98f, k = 0.5523f, kr = r*k, tipK = tipHalf*k
// 方向: 右向き (rdx=1, rdy=0)
moveTo(cx, cy - r)
cubicTo(cx-kr, cy-r,    cx-r, cy-kr,   cx-r, cy)                      // 左円弧
cubicTo(cx-r, cy+kr,    cx-kr, cy+r,   cx, cy+r)                      // 右円弧下半
cubicTo(cx+blobLen*0.72f, cy+r, cx+blobLen*0.92f, cy+tipHalf, cx+blobLen, cy+tipHalf) // 下テーパー
cubicTo(cx+blobLen+tipK, cy+tipHalf, cx+blobLen+tipHalf, cy+tipK, cx+blobLen+tipHalf, cy) // 先端
cubicTo(cx+blobLen+tipHalf, cy-tipK, cx+blobLen+tipK, cy-tipHalf, cx+blobLen, cy-tipHalf) // 先端上
cubicTo(cx+blobLen*0.92f, cy-tipHalf, cx+blobLen*0.72f, cy-r, cx, cy-r)  // 上テーパー戻り
close()
```

ボール描画: `drawIdleBall` の RadialGradient ハイライト込み。  
ブロブ塗り: LinearGradient (buttonColor 100% → 84% → 50%)。

#### 3. Revolver プレビュー ポインター修正

`drawPinnedRevolver` と完全一致するロジックに変更。先端がアイコン外側。詳細は Section 4-2。

#### 4. SliderSettingCard — スリムスライダー

M3 1.3.0 の `Slider(thumb = ..., track = ...)` カスタムラムダを使用。  
`@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)` をファイル先頭に追加。

```kotlin
thumb = {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Spacer(Modifier.size(14.dp).background(AccentColor, CircleShape))  // 14dp 視覚サイズ
    }
}
track = { state ->
    val fraction = ((state.value - state.valueRange.start) /
                   (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(2.dp)) {  // 2dp トラック高さ
        Box(Modifier.fillMaxSize().background(Color(0xFF1E2A3A), RoundedCornerShape(1.dp)))
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AccentColor, RoundedCornerShape(1.dp)))
    }
}
```

#### 5. FeatureGrid → FeatureList（グループ化リスト）

**変更前:** 3×2 アイコングリッド（意味不明瞭）  
**変更後:** カード内リスト、3グループ（アプリ管理 / 表示と動作 / その他）

各アイテムに:
- ラベル（太字）
- サブタイトル（説明文）
- 末尾に `›` シェブロン

#### 6. AppPickerDialog — アイコン・検索・複数選択

```kotlin
@Composable
private fun AppPickerDialog(
    allApps: List<AppEntry>,
    excluded: Set<String> = emptySet(),       // シングル選択: 除外（グレーアウト）
    initialSelected: Set<String> = emptySet(), // マルチ選択: 初期チェック状態
    multiSelect: Boolean = false,
    onSelect: (AppEntry) -> Unit = {},         // シングル選択コールバック
    onConfirm: (Set<String>) -> Unit = {},     // マルチ選択確定コールバック
    onDismiss: () -> Unit
)
```

新機能:
- **アイコン表示**: `cc.nativeCanvas.drawBitmap(iconBmp, null, RectF(...), null)` — 既存インポートのみ使用
- **検索**: `BasicTextField` + `remember(allApps, query) { filter }` — リアルタイムフィルタ
- **複数選択**: `var selected by remember { mutableStateOf(initialSelected) }` → Checkbox + 選択数表示 + 「完了」ボタン
- **除外アプリ**: `excluded` セットに含まれるアプリはグレーアウト + 「使用中」ラベル（シングル選択時のみ）

マルチ選択の保存フロー（非表示アプリ）:
```
HiddenAppsDialogContent
  → AppPickerDialog(multiSelect=true, initialSelected=config.hiddenPackages.toSet())
  → onConfirm { selected: Set<String> →
      onConfigChange(config.copy(hiddenPackages = selected.toList()))
      onDismiss()
    }
```

#### 7. PinnedAppRow — アイコン表示追加

`PinnedAppRow` の先頭に 32dp アイコン枠を追加。同じ Canvas drawBitmap アプローチ。

---

### 6-B. 画面レイアウト（現在）

```
[ヘッダーバー: "Pully Launcher"  [Close]]
[タブストリップ:  PULL  |  REVOLVER  ]  ← タップまたはスワイプで切替 (固定)
[HorizontalPager]
  Page 0 — PULL
    ─── 固定（スクロール外）───
    [PullPreviewCard: 200dp]    ← Canvas: buildBlobPath 数式 + RadialGradient ボール
    ─── スクロール可能 ─────────
    [基本設定]
      ボールサイズ / アイコンサイズ / 間隔 / ボール透明度
    [挙動設定]
      最初のアプリ距離 / 引っ張り距離 / キャンセル / アプリ数 / 非表示秒数
    [Pull設定をリセット]
    [関連機能リスト]
  Page 1 — REVOLVER
    ─── 固定（スクロール外）───
    [RevolverPreview: 210dp]    ← Canvas: 外開きポインター (オーバーレイと一致)
    "長押しで Revolver を起動 / 上下スワイプで回転"
    ─── スクロール可能 ─────────
    [基本設定]
      直径 / 速度 / アイコンサイズ / アーク間隔
    [選択位置]             ← 4ボタン選択 (上/右/下/左)
    [詳細設定 ▼]          ← 折りたたみ: エッジの濃さ / 背景の明るさ
    [Revolver設定をリセット]
    [関連機能リスト]
```

### 6-C. 関連機能リスト（全ページ共通）

グループ「アプリ管理」:
- ★ 固定アプリ — 「Revolver に表示するアプリを最大8件登録」
- ◎ 非表示アプリ — 「特定アプリ起動中に Pully を隠す」

グループ「表示と動作」:
- ● 配色テーマ — 「ボールとノードのカラーを変更」
- ▶ 撮影モード — 「スクリーン録画中 Pully を完全非表示」

グループ「その他」:
- ↑ 使用履歴 — 「最近使ったアプリを優先表示（権限が必要）」
- ⚙ 権限と起動 — 「オーバーレイ権限の確認と Pully の起動・停止」

### 6-D. 共通コンポーネント

**`SliderSettingCard`**: `Card { label + [−][DragNumberField][+] + hint + Slider(14dp thumb, 2dp track) }`

**`DragNumberField`**: タップ→編集 / 水平ドラッグ→値変更 / ハプティクス 4 ステップごと

**`SelectorPositionCard`**: 4 方向ボタン（選択中はアクセントカラー枠）

**`AppPickerDialog`**: アイコン(Canvas) + 検索(BasicTextField) + Checkbox(multi) + 選択数 + 完了ボタン

### 6-E. デザイントークン

```kotlin
private val BgColor       = Color(0xFF0C1018)  // 最背景
private val CardColor     = Color(0xFF141923)  // カード
private val CardBorderCol = Color(0xFF1E2A3A)  // カード枠
private val AccentColor   = Color(0xFF88C0D0)  // シアン
private val HintColor     = Color(0xFF7A9BB0)  // サブテキスト
private val DangerColor   = Color(0xFFBF5A5A)  // 赤（リセット/削除）
```

### 6-F. Pull プレビュー描画詳細

```kotlin
// scale = min(pw*0.9/neededW, ph*0.8/neededH)  フィットスケール
// cx = pw/2 - totalW/2 + sBR  (コンテンツ水平センタリング)
// blobLen = sBase + sSpc*(count-1)*0.60f  (60% 引き出し状態)
// 描画順: blob stroke → blob fill(LinearGradient) → nodes(glow+core) → centerBall → highlight → label
// 選択ノード = nodeCount / 2 番目  (グロー + 1.12× 拡大)
```

### 6-G. Galaxy 端末 確認状況

| 機能 | 状態 |
|------|------|
| プレビュー固定レイアウト | 未確認 |
| Pull プレビュー（buildBlobPath 数式） | 未確認 |
| Revolver ポインター（外開き） | 未確認 |
| スリムスライダー (14dp/2dp) | 未確認 |
| 関連機能リスト（グループ表示） | 未確認 |
| アプリピッカー（アイコン・検索・複数選択） | 未確認 |

---

## 7. V1 Pull 機能への影響

今回の変更で Pull 機能は**一切変更なし**:
- `nodeRadiusPx`, `spacingPx` は Pull 側の描画のみに使われる
- `OverlayTouchView` の DRAGGING ステートマシン: 変更なし
- `OverlayExpandView` の `drawNode`, `drawBlob`, `revealProgress`: 変更なし
- `LauncherUiConfig.kt` / `UiConfigPrefs.kt` / OverlayService 系: 変更なし

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
e102d80 (設定UI改善)         — assembleDebug SUCCESSFUL,  lintDebug CLEAN  ← @OptIn(ExperimentalMaterial3Api) 追加で解決
6b74aa3 (設定画面リデザイン) — assembleDebug SUCCESSFUL,  lintDebug CLEAN
22edc98 (V2 設定・描画改良)  — assembleDebug SUCCESSFUL,  lintDebug CLEAN
601e6c2 (V2 UX 調整)         — assembleDebug SUCCESSFUL,  lintDebug CLEAN
f24e487 (V2 実装)             — assembleDebug SUCCESSFUL,  lintDebug CLEAN
```

ビルド注意: `Slider(thumb = ..., track = ...)` の `SliderState` は M3 1.3.0 で `@ExperimentalMaterial3Api`。
ファイル先頭に `@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)` が必要。

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
- SharedPreferences の既存キーを削除する — 禁止

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
- [ ] 円形選択枠がない。山括弧ポインターが選択アイコン外側に表示される
- [ ] 薄いアークガイドが軌道に沿って表示される
- [ ] 選択中アイコンが拡大・グロー・アプリ名表示
- [ ] 端アイコンが滑らかにフェードする

### 設定画面 (6b74aa3)
- [ ] PULL / REVOLVER タブが切替可能（タップ＋スワイプ）
- [ ] Pull ページ: プレビューにブロブ＋ノード表示
- [ ] Revolver ページ: プレビューに内側ポインター（Pully と選択アイコンの間）
- [ ] スライダー変更がプレビューに即時反映
- [ ] − / + ボタンで1ステップずつ変更
- [ ] 数値欄を左右ドラッグで連続変更 / タップでキーボード入力
- [ ] Pull / Revolver リセットボタンがデフォルト値に戻す（確認ダイアログ付き）
- [ ] 関連機能グリッドのカードが対応ダイアログを開く（固定アプリ / 非表示 / 配色 / 撮影 / 履歴 / 権限）
- [ ] 詳細設定（折りたたみ）が展開/折りたたみできる

### リボルバー専用設定
- [ ] 直径スライダー → リング半径が変化 (1.5〜4.0)
- [ ] 速度スライダー → 回転速度が変化 (50%〜200%)
- [ ] アイコンサイズスライダー → Pull 側と独立して変化 (0.5〜1.8)
- [ ] アプリ間隔スライダー → アーク角が変化 (0.4〜1.8)
- [ ] 選択位置変更 → 即時反映

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
4. 数値ドラッグの感度（22f px/step）を実機操作感に基づき調整
