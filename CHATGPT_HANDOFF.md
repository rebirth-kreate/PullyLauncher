# CHATGPT_HANDOFF.md

最終更新: 2026-06-17

---

## 今回の目的

「PullyLauncherを起動すると、画面が一瞬表示されたあと、すぐに終了してホーム画面へ戻ってしまいます」の根本原因特定と修正（エミュレーター実機確認済み）。

---

## デバッガー切断 vs プロセス終了の判定

**プロセス終了（クラッシュ）でした。**

```
adb shell pidof com.rebirthkreate.pullylauncher
→ '' (空)  ← 修正前
→ '22562'  ← 修正後、5秒・10秒後も生存
```

---

## 実際の例外全文

```
06-16 15:10:09.608  E AndroidRuntime: FATAL EXCEPTION: main
06-16 15:10:09.608  E AndroidRuntime: Process: com.rebirthkreate.pullylauncher, PID: 22116
06-16 15:10:09.608  E AndroidRuntime: java.lang.RuntimeException: Unable to instantiate activity
    ComponentInfo{com.rebirthkreate.pullylauncher/com.rebirthkreate.pullylauncher.MainActivity}
06-16 15:10:09.608  E AndroidRuntime: Caused by: java.lang.ClassNotFoundException:
    Didn't find class "com.rebirthkreate.pullylauncher.MainActivity" on path:
    DexPathList[[zip file ".../base.apk"], nativeLibraryDirectories=[.../x86_64, ...]]
06-16 15:10:09.613  W ActivityTaskManager: Force finishing activity
    com.rebirthkreate.pullylauncher/.MainActivity
```

---

## 前回の ConcurrentModificationException 修正について

**有効性が未確認のまま終わっていた**（Kotlinが全くコンパイルされていなかったため、前回修正コードも DEX に存在していなかった）。

---

## 本当の根本原因

**`org.jetbrains.kotlin.android` プラグインが初回コミットから一度も適用されていなかった。**

| 問題点 | 詳細 |
|---|---|
| 欠けていたプラグイン | `org.jetbrains.kotlin.android` |
| 存在していたプラグイン | `org.jetbrains.kotlin.plugin.compose`（Compose compiler addon のみ） |
| 影響 | Kotlinソースファイルが**一切コンパイルされない** |
| APKの内容 | Rクラス（javac生成）＋サードパーティライブラリのDEXのみ |
| クラッシュ | `ClassNotFoundException: MainActivity` が起動直後に発生 |

**なぜビルドが「SUCCESSFUL」だったか**:
- AGPはKotlinプラグインがなくてもエラーを出さない（Kotlinファイルを無視するだけ）
- `BuildConfig` の未解決参照エラーも Kotlin コンパイルタスク自体が存在しないため表示されなかった
- Rクラス（Java）はAGPのリソースパイプラインで別途コンパイルされるためDEXに含まれていた

**なぜ「一瞬表示」されていたか**:
Android Studio の起動コマンドに含まれる `--splashscreen-show-icon` でスプラッシュ画面（アプリアイコン）が一瞬表示されてから `ClassNotFoundException` で即クラッシュしていた。

---

## 問題のファイルと行

| ファイル | 問題 |
|---|---|
| `gradle/libs.versions.toml` | `[plugins]` に `kotlin-android` が未定義 |
| `build.gradle.kts`（ルート） | `kotlin-android` の宣言なし |
| `app/build.gradle.kts` | `alias(libs.plugins.kotlin.android)` 未適用 / `kotlinOptions` 未設定 / `buildConfig = true` 未設定 |

---

## 修正内容

### `gradle/libs.versions.toml`

```toml
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }  # ← 追加
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

### `build.gradle.kts`（ルート）

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false  // ← 追加
    alias(libs.plugins.kotlin.compose) apply false
}
```

### `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)   // ← 追加：Kotlinコンパイル有効化
    alias(libs.plugins.kotlin.compose)
}

android {
    // ...
    compileOptions { ... }
    kotlinOptions { jvmTarget = "11" }  // ← 追加
    buildFeatures {
        compose = true
        buildConfig = true  // ← 追加：BuildConfigクラス生成
    }
}
```

---

## エミュレーター実機確認結果

テスト環境: Pixel 9a エミュレーター (API 35, x86_64)

### 上書きインストール（`adb install -r`）

```
Status: ok  LaunchState: COLD  TotalTime: 1675  WaitTime: 1681
PID at 5s:  '22562'   ← 生存
PID at 10s: '22562'   ← 生存
PullyStartup: MainActivity onCreate overlay=false service=false
PullyStartup: MainActivity onStart
PullyStartup: MainActivity onResume overlay=false service=false appsLoaded=0
PullyApps: loadAll start thread=DefaultDispatcher-worker-1
PullyApps: loadAll done appCount=19 iconCount=19
FATAL EXCEPTION: なし
onDestroy finishing=true: なし
```

### クリーンインストール（`adb uninstall` → `adb install`）

```
Status: ok  LaunchState: COLD  TotalTime: 1782  WaitTime: 1789
PID at 8s: '22914'   ← 生存
PullyStartup: MainActivity onCreate overlay=false service=false
PullyStartup: MainActivity onStart
PullyStartup: MainActivity onResume overlay=false service=false appsLoaded=0
PullyApps: loadAll done appCount=19 iconCount=19
FATAL EXCEPTION: なし
```

両ケースで確認完了。

---

## ビルド結果

```
.\gradlew.bat clean assembleDebug --no-daemon
BUILD SUCCESSFUL in 21s
37 actionable tasks: 37 executed

.\gradlew.bat lintDebug --no-daemon
BUILD SUCCESSFUL in 19s
Lint errors: 0
```

Kotlin コンパイル警告（エラーではない）:
- `MOVE_TO_FOREGROUND` deprecated (UsageHistoryRepository.kt:78)
- `checkOpNoThrow` deprecated (UsageHistoryRepository.kt:93)
- GestureMath.kt: shadowed extension operators
- SettingsScreen.kt: `LocalLifecycleOwner` deprecated

---

## Git 履歴

```
4277b26 fix: resolve remaining startup process termination
d2e61e0 docs: update CHATGPT_HANDOFF for startup crash fix
dd54412 chore: remove .idea/deploymentTargetSelector.xml from tracking
e3ac651 fix: prevent PullyLauncher from closing on startup
fe3ca68 fix: ensure package broadcasts and app updates refresh
f100503 fix: refresh launcher apps on package changes
60e4e37 Update to Version 2
```

push 先: https://github.com/rebirth-kreate/PullyLauncher (main ブランチ)

---

## 現在の Gradle 構成

| コンポーネント | バージョン |
|---|---|
| AGP | 8.10.1 |
| Gradle | 8.11.1 |
| Kotlin | 2.1.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| applicationId | `com.rebirthkreate.pullylauncher` |

---

## Logcatフィルタ（今後の実機確認用）

```
tag:PullyStartup tag:PullyApps
```

---

## 残留 Kotlin 警告（後回し可）

| ファイル | 警告 | 対応方針 |
|---|---|---|
| `UsageHistoryRepository.kt:78` | `MOVE_TO_FOREGROUND` deprecated | UsageEvents.Event.MOVE_TO_FOREGROUND に変更 |
| `UsageHistoryRepository.kt:93` | `checkOpNoThrow` deprecated | AppOpsManager.unsafeCheckOpNoThrow 等に変更 |
| `GestureMath.kt:19-27` | shadowed extension operators | `operator fun times/plus/minus` を削除して標準メンバーを使う |
| `SettingsScreen.kt:63` | `LocalLifecycleOwner` deprecated | `androidx.lifecycle.compose` パッケージへ移行 |

---

## 次に実施すべきこと

1. **Galaxy実機での確認** — エミュレーター確認済み。実機でも同様に起動することを確認
2. オーバーレイパーミッション付与後にフローティングボタンが表示されるか確認
3. プライバシーポリシーを Web ページとして公開（GitHub Pages 等）
4. keystore 生成・release ビルド設定
5. Play Console — Data Safety・Foreground Service 申告

---

## ChatGPTに相談したいこと

1. Galaxy実機での起動確認結果
2. 上記の deprecated 警告を修正すべきか（Play Store 審査に影響するか）
3. プライバシーポリシーの公開方法
4. フローティングボタン動作確認（オーバーレイ権限付与後）
