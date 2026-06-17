# PullyLauncher-V1-Windows — ChatGPT Handoff

> Branch: `fix/v1-hidden-apps-secure-overlay`
> HEAD: `f24e487` (pushed to origin)
> Base: `origin/recovery/mac-latest-20260617`
> Date: 2026-06-17
> Status: **BUILD SUCCESSFUL, LINT CLEAN — Galaxy device re-testing pending (V2 features not yet device-tested)**

---

## 1. Project Overview

PullyLauncher is a floating launcher for Android. It draws a draggable ball overlay (via `WindowManager.TYPE_APPLICATION_OVERLAY`) that the user can pull to reveal a radial app menu. Written entirely in Kotlin; uses Jetpack Compose for Settings screens, custom `Canvas`-based Views for the overlay drawing.

**Package:** `com.example.pullyluncher`
**Min SDK:** 26 (Oreo) | **Target/Compile SDK:** 36 | **AGP:** 9.0.0 | **Kotlin:** 2.2.10

> AGP 9.0+ uses built-in Kotlin. Do NOT add `org.jetbrains.kotlin.android` — it is absent by design.

---

## 2. Galaxy Device Test History

### Previously Confirmed (Galaxy) ✅
- Hidden apps detection: open hidden app → Pully hides within ~750ms; return to Home → Pully shows
- Basic overlay rendering and gestures

### Galaxy Test Results After f081e36 — 3 NEW PROBLEMS ❌

After commit `f081e36 fix: limit secure overlay to launcher drawing bounds`:

1. **Pully visible but untouchable**
   - Root cause: `showOverlay()` had an early-return guard (`drawView VISIBLE && alpha >= 1f → return`) that skipped removing `FLAG_NOT_TOUCHABLE` from touchView. Animator race condition also caused stale `endAction` to set `View.INVISIBLE` after a subsequent `showOverlay()` had already started.

2. **Pully not re-appearing after returning from hidden app to Home**
   - Root cause: Same Animator race condition — a stale `hideOverlay()` endAction fired after `showOverlay()` had already run, overriding visibility to INVISIBLE. Also: the early-return guard in `showOverlay()` could incorrectly leave FLAG_NOT_TOUCHABLE set.

3. **FLAG_SECURE still causes black rectangle / screenshot blocking**
   - Root cause: Even a small window with FLAG_SECURE causes the Android compositor to mark that layer as secure. The entire window (even if ~236×236px) appears as a black rectangle in screenshots and Device Mirroring.
   - **Decision: remove FLAG_SECURE completely. Replace with Capture Mode.**

---

## 3. Commits Applied (This Session)

### `d554874` — fix: restore reliable overlay touch and visibility state

**Changes:**
- `OverlayService.kt`: Full rewrite of hide/show logic
  - `HiddenReasons` data class with three boolean fields: `hiddenApp`, `temporaryHide`, `captureMode`
  - `updateOverlayVisibility()` is now the sole dispatch point
  - `applyHide()`: increments `visibilityGeneration`, calls `setTouchable(false)` immediately (before animation), then fades out with `withEndAction` that only fires if generation matches
  - `applyShow()`: increments generation, calls `setTouchable(true)` BEFORE setting visibility to VISIBLE, then fades in — ensures FLAG_NOT_TOUCHABLE is always cleared regardless of current alpha
  - `setTouchable(Boolean)`: updates `FLAG_NOT_TOUCHABLE` on touchParams + calls `updateViewLayout` only when flag actually changes
  - `resolveHomeLauncherPackage()`: resolves home launcher via `ACTION_MAIN / CATEGORY_HOME` for debug logging
  - `PullyVisibility` debug log now emits: pkg, home launcher, hiddenApp, temporaryHide, captureMode, effectiveHidden, drawVis/Alpha, touchVis/Alpha, notTouchable, x/y/w/h — only on state change
  - Removed: `drawOriginX/Y`, `DRAW_IDLE_PADDING_PX`, `isTemporarilyHidden`, `applySecureOverlay()`, `updateDrawWindowToIdle()`, `updateDrawWindowToDrag()`, `hideOverlay()`, `showOverlay()`

- `OverlayExpandView.kt`:
  - Removed `windowOriginX` and `windowOriginY` fields
  - Removed `canvas.save()` / `canvas.translate(-originX, -originY)` / `canvas.restore()` from `onDraw`
  - draw Window is MATCH_PARENT; drawing uses screen coordinates directly (no transform needed)

- `OverlayTouchView.kt`:
  - Removed `onDragStateChanged` callback and all invocations of it
  - draw Window no longer needs resize signals (it is always MATCH_PARENT)

### `f24e487` — feat: implement V2 revolver menu with long-press pinned apps ring

**Changed files:**
- `model/SelectorPosition.kt` *(new)* — `TOP/RIGHT/BOTTOM/LEFT` enum; `angleDeg: Float`, `displayName: String`
- `model/LauncherAction.kt` *(new)* — `sealed interface LauncherAction`; `AppLaunchAction(packageName, label)`
- `model/LauncherUiConfig.kt` — added `selectorPosition: SelectorPosition = SelectorPosition.RIGHT`
- `data/UiConfigPrefs.kt` — save/load `selectorPosition` via `KEY_SELECTOR_POSITION = "selector_position"`
- `LauncherRepository.kt` — `MAX_PINS` 3 → 8
- `OverlayTouchView.kt` — added `PINNED_MENU` TouchState, revolver rotation, cancel zone, `cancelGesture()`
- `OverlayExpandView.kt` — added `drawPinnedRevolver()`, `computeRevolverRadius()`, cos/sin drawing
- `OverlayService.kt` — `touch.onLaunchPinnedApp = { pkg -> launchApp(pkg) }`, `tv.cancelGesture()` in `applyHide()`
- `ui/theme/SettingsScreen.kt` — Revolver Menu section with 4-position selector buttons
- `res/values*/strings.xml` (3 files) — `section_revolver`, `hint_revolver_selector_position`, updated `pinned_apps_description`

**Build:** assembleDebug SUCCESSFUL, lintDebug CLEAN

---

### `14e84af` — feat: add 3-second capture mode delay and make it session-only

**Changes:**
- `OverlayService.kt`:
  - `applyCaptureMode(wantCapture: Boolean)` added:
    - `wantCapture=true`: 3秒後に `hiddenReasons.captureMode = true` → `applyHide()` + `updateNotification()`
    - `wantCapture=false`: 即座にジョブキャンセル + `captureMode = false` → `applyShow()` + `updateNotification()`
    - `captureHideJob` で二重起動を防止。すでに有効 or 遅延中ならスキップ
  - `applyBallVisibility()`: 直接 `hiddenReasons.captureMode` を更新するのをやめ、`applyCaptureMode()` を経由
  - `captureHideJob: Job?` フィールド追加
  - `CAPTURE_HIDE_DELAY_MS = 3_000L` 定数追加
- `UiConfigPrefs.kt`:
  - `KEY_CAPTURE_MODE` 定数を削除
  - `save()` から `.putBoolean(KEY_CAPTURE_MODE, ...)` 削除
  - `load()` から `captureMode = prefs.getBoolean(...)` 削除
  - captureMode は SharedPreferences に保存されない。再起動で常に `false` に戻る

### `bb59544` — feat: replace secure capture blocking with capture mode

**Changes:**
- `LauncherUiConfig.kt`: Removed `secureOverlay: Boolean`. Added `captureMode: Boolean = false`
- `UiConfigPrefs.kt`: Removed `KEY_SECURE_OVERLAY`. Added `KEY_CAPTURE_MODE = "capture_mode"`. Updated `save()` and `load()`.
- `SettingsScreen.kt`: Replaced "Screenshot Exclusion" Switch section with "Capture Mode" Switch section. Uses `config.captureMode` / `R.string.section_capture_mode`, `setting_capture_mode`, `hint_capture_mode`.
- `OverlayService.kt` additions in this commit:
  - `ACTION_ENABLE_CAPTURE_MODE` / `ACTION_DISABLE_CAPTURE_MODE` intent actions
  - `onStartCommand()` handles these: persists to `LauncherUiConfig`, updates `hiddenReasons.captureMode`, calls `updateOverlayVisibility()` + `updateNotification()`
  - `buildNotification()`: if capture mode → title = "PullyLauncher — 撮影モード中", text = "録画のため Pully を非表示中です", action = "Pullyを再表示" (PendingIntent → `ACTION_DISABLE_CAPTURE_MODE`)
  - `updateNotification()`: annotated `@SuppressLint("NotificationPermission")` (updating a foreground service's own notification does not require POST_NOTIFICATIONS)
- All three `strings.xml` files (`values/`, `values-ja/`, `values-en/`):
  - Removed: `section_secure_overlay`, `setting_secure_overlay`, `hint_secure_overlay`
  - Added: `section_capture_mode`, `setting_capture_mode`, `hint_capture_mode`, `notif_running_text`, `notif_capture_mode_title`, `notif_capture_mode_text`, `notif_show_pully`

---

## 4. V2 Revolver Menu Architecture

### Touch State Machine (updated)

```
IDLE → PRESSING (long-press timer starts)
         ├─ moved > 28px before 500ms → DRAGGING  (V1 drag mode)
         ├─ 500ms + pinnedApps.isNotEmpty() → PINNED_MENU  (V2)
         └─ 500ms + pinnedApps.isEmpty() → MOVING  (V1 ball reposition)
```

### PINNED_MENU State

```
Enter:  isPinnedMenuOpen=true, rotoOffset=0f, selectedPinnedIndex=0, inCancelZone=false
Move:   applyRevolverMove(rawX, rawY)
          dist = sqrt((rawX-touchDownX)²+(rawY-touchDownY)²)
          inCancelZone = dist < buttonRadiusPx * REVOLVER_CANCEL_ZONE_RATIO (1.2f)
          speedFactor  = lerp(REVOLVER_SPEED_NEAR(4.0f), REVOLVER_SPEED_FAR(0.5f), dist/REVOLVER_SPEED_MAX_DIST_PX(300f))
          deltaOffset  = -(rawY - lastRawY) * speedFactor / REVOLVER_BASE_PX_PER_ITEM(100f)
          rotoOffset   = ((rotoOffset + deltaOffset) % count + count) % count
          newIdx       = rotoOffset.roundToInt() % count
          if newIdx != selectedPinnedIndex → haptic + selectedPinnedIndex = newIdx
Up:     inCancelZone → cancel (reset state); else → onLaunchPinnedApp(pinnedApps[selectedPinnedIndex].packageName)
```

### Revolver Drawing (`drawPinnedRevolver`)

```
ringRadius = computeRevolverRadius()
           = buttonRadiusPx * REVOLVER_RING_RATIO (2.4f)
             clamped to keep items inside screen edges

selectorAngle = cfg.selectorPosition.angleDeg  (RIGHT=0, TOP=-90, BOTTOM=90, LEFT=180)
itemAngle(i)  = selectorAngle + (i - rotoOffset) * (360f / count)

Cancel zone: filled circle radius = buttonRadiusPx * 1.2f
             glow stronger when inCancelZone
Selector: white rectangle frame at selectorAngle, fixed on ring
Items:    node + icon + glow; selected item gets larger glow + white border
Label:    selected app name below Pully (or above if near screen bottom)
```

### Key Constants (adjustable)

| Constant | Value | Location | Purpose |
|----------|-------|----------|---------|
| `REVOLVER_CANCEL_ZONE_RATIO` | `1.2f` | OverlayTouchView | Cancel zone radius multiplier |
| `REVOLVER_SPEED_NEAR` | `4.0f` | OverlayTouchView | Rotation speed when finger is near center |
| `REVOLVER_SPEED_FAR` | `0.5f` | OverlayTouchView | Rotation speed when finger is far from center |
| `REVOLVER_SPEED_MAX_DIST_PX` | `300f` | OverlayTouchView | Distance at which FAR speed is reached |
| `REVOLVER_BASE_PX_PER_ITEM` | `100f` | OverlayTouchView | Base px-per-item scroll unit |
| `REVOLVER_RING_RATIO` | `2.4f` | OverlayExpandView | Ring radius = button * ratio |
| `MOVE_CANCEL_PX` (long-press gate) | `28` | OverlayTouchView | Movement threshold to cancel long-press and start drag |
| `MAX_PINS` | `8` | LauncherRepository | Maximum pinned apps |

### LauncherAction Extension Points

```kotlin
sealed interface LauncherAction {
    val label: String
    val iconPackage: String?
    fun execute(context: Context)
}
data class AppLaunchAction(val packageName: String, override val label: String) : LauncherAction
// Future: ARKAction, SystemAction, ShortcutAction, etc.
```

---

## 5. Current Overlay Architecture

### Window Summary

```
[drawView]  OverlayExpandView
  Size:      MATCH_PARENT × MATCH_PARENT (full screen)
  Flags:     FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
             (no FLAG_SECURE — ever)
  Role:      Renders ball, blob, app nodes. No touch input.
  Drawing:   Uses screen coordinates directly (no canvas.translate needed).

[touchView] OverlayTouchView
  Size:      Ball diameter (buttonRadiusPx × 2)
  Flags:     FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
             (no FLAG_SECURE)
  Role:      Receives touch events only. Completely transparent.
```

### Hide/Show State Machine

```
HiddenReasons { hiddenApp, temporaryHide, captureMode }
  any = hiddenApp || temporaryHide || captureMode
  → updateOverlayVisibility()
      → applyHide()  if any == true
      → applyShow()  if any == false

applyHide():
  1. visibilityGeneration++  (gen = new value)
  2. setTouchable(false)     — FLAG_NOT_TOUCHABLE added IMMEDIATELY
  3. touchView.isEnabled = false
  4. cancel existing animators
  5. dv.animate().alpha(0f).withEndAction { if gen==current: INVISIBLE }
  6. tv.animate().alpha(0f)

applyShow():
  1. visibilityGeneration++
  2. cancel existing animators
  3. setTouchable(true)      — FLAG_NOT_TOUCHABLE removed BEFORE visible
  4. dv/tv visibility = VISIBLE (if not already), alpha = 0f
  5. touchView.isEnabled = true
  6. dv/tv animate().alpha(1f)
```

### Hidden Reasons — Who Updates What

| Field | Updated by |
|-------|-----------|
| `hiddenApp` | `applyBallVisibility()` (called from `pollForegroundPackage()`, `onConfigUpdated()`, `addOverlayViews()`) |
| `temporaryHide` | `temporarilyHideOverlay()` (double-tap); reset after `temporaryHideSeconds` |
| `captureMode` | `applyBallVisibility()` (reads from `LauncherRepository.config`); also direct update in `onStartCommand()` for notification actions |

---

## 5. Capture Mode

### Behavior

- `captureMode` は **セッション限定** — SharedPreferences に保存しない。サービス再起動で常に `false`
- ON 時の流れ:
  1. `LauncherRepository.config.captureMode = true` にセット
  2. `applyCaptureMode(true)` を呼ぶ
  3. **3秒後** に `hiddenReasons.captureMode = true` → Pully が alpha=0 / INVISIBLE / NOT_TOUCHABLE に
  4. 通知タイトル "PullyLauncher — 撮影モード中"、アクション "Pullyを再表示" が表示される
- OFF（復帰）の流れ（いずれかのパス）:
  - **通知アクション**: `ACTION_DISABLE_CAPTURE_MODE` → `applyCaptureMode(false)` → 即座に復帰
  - **設定画面 Switch**: `onConfigChange(config.copy(captureMode = false))` → `onStartCommand(ACTION_DISABLE_CAPTURE_MODE)` → 同上
  - **サービス再起動**: `UiConfigPrefs.load()` で captureMode は常に `false` → 起動時から captureMode=OFF

### スクショ検知 API

V1 では採用しない。自動 OFF のトリガーは手動操作（上記3パス）のみ。

### キャンセル保護

`captureHideJob?.isActive == true` の場合は `applyCaptureMode(true)` で二重起動を防止。3秒ディレイ中に OFF が呼ばれると `captureHideJob?.cancel()` でジョブをキャンセルし、Pully は隠れない。

Settings UI: Section "撮影モード / Capture Mode" with Switch (config.captureMode). Switch が ON になると `onStartCommand(ACTION_ENABLE_CAPTURE_MODE)` が呼ばれる。

---

## 6. UsageEvents Foreground Detection (unchanged)

750ms polling, cursor-based. Only `MOVE_TO_FOREGROUND` (type=1). Home launcher is NOT excluded from detection — returning to Home correctly triggers `showOverlay()`.

| Phase | lookbackMs | afterTimestampMs |
|-------|-----------|-----------------|
| Initial | 60,000ms | 0L |
| Normal | 5,000ms | lastFgEventTimestampMs |

`IGNORED_PACKAGES = {"com.android.systemui", "android"}` — home launcher NOT in this set.

---

## 7. Build Configuration

```
gradle/libs.versions.toml   — agp = "9.0.0" (local modification, DO NOT CHANGE)
gradle/wrapper/gradle-wrapper.properties — Gradle 9.1.0 (local, DO NOT CHANGE)
```

Build/lint commands:
```powershell
$env:JAVA_HOME = "C:\Users\Ryon\AppData\Local\Programs\Android Studio\jbr"
.\gradlew.bat clean assembleDebug --no-daemon
.\gradlew.bat lintDebug --no-daemon
```

Check debug builds: `applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE` (no BuildConfig.DEBUG).

---

## 8. Build & Lint Results

```
f24e487 (V2 revolver) — assembleDebug SUCCESSFUL in 19s, lintDebug CLEAN
14e84af (captureMode) — assembleDebug SUCCESSFUL in 21s, lintDebug CLEAN
(pre-existing GestureMath.kt shadow warnings are non-blocking in all builds)
```

---

## 9. Commit & Push Result

```
f24e487 feat: implement V2 revolver menu with long-press pinned apps ring  ← NEW
14e84af feat: add 3-second capture mode delay and make it session-only
bb59544 feat: replace secure capture blocking with capture mode
d554874 fix: restore reliable overlay touch and visibility state
f081e36 fix: limit secure overlay to launcher drawing bounds
80e2a43 fix: correct usage event foreground tracking
dfe1c92 feat: exclude overlay from screenshots and recordings
c8efe1f fix: replace accessibility foreground detection with usage events
712d16e backup: preserve latest Mac PullyLauncher version
```

---

## 10. Key Files Reference

| File | Purpose |
|------|---------|
| `OverlayService.kt` | Window creation, polling, HiddenReasons, applyHide/applyShow, captureMode, notification |
| `OverlayTouchView.kt` | Touch state machine (IDLE/PRESSING/MOVING/DRAGGING/PINNED_MENU), revolver rotation |
| `OverlayExpandView.kt` | Canvas drawing — radial menu + revolver ring (MATCH_PARENT, no coord transform) |
| `LauncherRepository.kt` | Singleton: config, apps, foreground package, MAX_PINS=8 |
| `UsageHistoryRepository.kt` | UsageStats queries |
| `UiConfigPrefs.kt` | SharedPreferences for LauncherUiConfig (incl. selectorPosition) |
| `LauncherUiConfig.kt` | All user-configurable settings (captureMode, selectorPosition) |
| `model/SelectorPosition.kt` | Enum: TOP/RIGHT/BOTTOM/LEFT with angleDeg + displayName |
| `model/LauncherAction.kt` | Sealed interface for extensible launcher actions (AppLaunchAction) |
| `SettingsScreen.kt` | Compose Settings UI (Capture Mode + Revolver Menu sections) |

---

## 11. What Needs Galaxy Device Re-Testing

### Previously Confirmed (Galaxy) ✅
- Hidden apps detection
- Basic overlay rendering and gestures

### Must Re-Test After bb59544

1. **Touch input (was broken after f081e36)**
   - Tap Pully → goes Home ✓
   - Long-press → ball moves ✓
   - Pull → radial menu opens, apps launchable ✓
   - Double-tap → temporary hide, then auto-restore ✓

2. **Home re-appearance (was broken after f081e36)**
   - Open hidden app → Pully hides within ~750ms ✓
   - Return to Home (press Home button) → Pully re-appears within ~750ms ✓
   - Switch to a non-hidden app → Pully re-appears ✓

3. **V2 Revolver Menu (new feature, NOT yet confirmed on Galaxy)**
   - Long-press Pully with ≥1 pinned app → ring appears with pinned apps
   - Slide finger up/down → items rotate (faster near center, slower far away)
   - Lift in cancel zone (< 1.2× button radius from touch point) → cancel, no launch
   - Lift outside cancel zone → launches selected app
   - Settings → Revolver Menu → selector position picker works (TOP/RIGHT/BOTTOM/LEFT)
   - Ring stays on screen when Pully is near an edge (computeRevolverRadius)
   - With 0 pinned apps: long-press goes to V1 ball-reposition mode (MOVING)
   - All V1 gestures unchanged: tap=Home, pull=radial menu, double-tap=hide

4. **Capture mode (new feature, NOT yet confirmed on Galaxy)**
   - Enable Capture Mode in Settings → Pully fully disappears ✓
   - Take screenshot → Pully NOT visible in screenshot, no black rectangle ✓
   - Android Studio Device Mirroring → NOT black ✓
   - Notification shows "撮影モード中" title + "Pullyを再表示" button ✓
   - Tap notification action → Pully re-appears, notification restores ✓
   - Disable Capture Mode in Settings → Pully re-appears ✓

5. **Regression checks**
   - Pull gesture → radial menu, all nodes selectable
   - Long-press → ball repositions, position saves after restart
   - Pinned apps and usage history ordering
   - Hidden app list management (add/remove)
   - Permission UIs (overlay, usage access)
   - Foreground service notification normal state

---

## 12. Prohibited Operations

These are permanently prohibited on this branch:

- `git reset --hard` — PROHIBITED
- `git clean` — PROHIBITED
- Force push — PROHIBITED
- Branch change — PROHIBITED
- applicationId / namespace / package change — PROHIBITED
- Full rewrite of UsageEvents foreground detection — PROHIBITED
- AccessibilityService re-introduction — PROHIBITED
- Removing or replacing the V2 revolver implementation without explicit approval
- Discarding local Gradle changes (AGP 9.0.0, Gradle 9.1.0, distributionSha256Sum) — PROHIBITED
- Claiming "PASS" for anything not confirmed on real Galaxy device

---

## 13. Permissions Required

| Permission | Purpose |
|-----------|---------|
| `SYSTEM_ALERT_WINDOW` | Draw overlay windows |
| `FOREGROUND_SERVICE` | Run as foreground service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | API 34+ specialUse type |
| `VIBRATE` | Haptic feedback |
| `PACKAGE_USAGE_STATS` | Foreground app detection + usage history ordering |

---

## 14. Debug Logging

### PullyVisibility (debug builds only — state-change only)

```
D/PullyVisibility: foreground[initial(60s)] before=null after=com.android.chrome home=com.samsung.android.launcher eventTs=...
D/PullyVisibility: pkg=com.samsung.android.launcher home=com.samsung.android.launcher hiddenApp=false tmpHide=false capture=false effectiveHidden=false drawVis=VISIBLE drawAlpha=1.00 touchVis=VISIBLE touchAlpha=1.00 notTouchable=false x=42 y=262 w=160 h=160
D/PullyVisibility: pkg=com.example.hiddenapp home=com.samsung.android.launcher hiddenApp=true tmpHide=false capture=false effectiveHidden=true drawVis=INVISIBLE drawAlpha=0.00 touchVis=INVISIBLE touchAlpha=0.00 notTouchable=true x=42 y=262 w=160 h=160
```

### PullyEvents (per-event verbose — enable with adb)

```bash
adb shell setprop log.tag.PullyEvents D
```

```
D/PullyEvents: accept type=1 pkg=com.example.camera ts=...
D/PullyEvents: skip type=19 pkg=... reason=notForeground
```
