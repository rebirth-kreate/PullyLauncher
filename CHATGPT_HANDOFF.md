# CHATGPT_HANDOFF.md

最終更新: 2026-06-16

---

## 今回の目的

Google Play 公開版の権限・ポリシー対応。AccessibilityService を完全削除し、
UsageStats 専用構成に変更。Foreground Service Manifest 対応・通知権限対応・
バックアップ設定も実施。

---

## 完了したこと

### 1. AccessibilityService 完全削除

| 対応内容 | 結果 |
|---|---|
| `ForegroundAppService.kt` を削除 | 完了 |
| `res/xml/accessibility_service_config.xml` を削除 | 完了 |
| `AndroidManifest.xml` から `<service>` 宣言・`BIND_ACCESSIBILITY_SERVICE` を削除 | 完了 |
| `OverlayService.kt` から `ForegroundAppService.isRunning` 参照を削除 | 完了 |
| `LauncherRepository.kt` から `onForegroundChanged` コールバックを削除 | 完了 |
| `SettingsScreen.kt` から「ユーザー補助を開く」ボタン・フォアグラウンド検知セクションを削除 | 完了 |
| `strings.xml` から accessibility 関連文字列を削除 | 完了 |
| Merged Manifest に AccessibilityService が残っていないことを確認 | **OK** |

### 2. 非表示アプリ機能を UsageStats 専用に変更

- SettingsScreen の非表示アプリ警告を UsageStats のみに変更:
  ```
  非表示アプリ機能を使用するには、「使用履歴へのアクセス」を許可してください。
  この権限は、現在表示中のアプリを端末内で判定するためだけに使用します。
  利用履歴を保存・送信することはありません。
  ```
- 「使用履歴へのアクセスを開く」ボタンのみ表示（ユーザー補助ボタンを削除）
- ON_RESUME で権限再確認する DisposableEffect は UsageStats 専用に変更

### 3. UsageStats ポーリング最適化

- ポーリング間隔: **850ms**（定数 `POLL_INTERVAL_MS`）
- **画面消灯中はスキップ**（`PowerManager.isInteractive` で判定）
- **権限なし時もスキップ**（フローティングランチャー自体は動作継続）
- 同じパッケージ名が続く場合は表示更新なし
- Service 終了時に CoroutineScope.cancel() で確実にキャンセル
- 新しいデバッグログ形式（BuildConfig.DEBUG のみ）:
  ```
  [UsageStats] fg=<package>
  hidden=true/false match=true/false result=SHOW/HIDE permission=true/false
  ```

### 4. Foreground Service specialUse Manifest 対応

`OverlayService` の `<service>` 内に `<property>` を追加:
```xml
<property
    android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="Maintains a user-enabled floating launcher overlay so it remains available while other apps are in use." />
```

Merged Manifest に specialUse property が含まれていることを確認済み。

### 5. POST_NOTIFICATIONS 対応

- Manifest に `android.permission.POST_NOTIFICATIONS` を追加
- フローティング開始ボタンを押したとき、Android 13+ で権限未付与の場合のみ説明ダイアログを表示:
  ```
  Pullyを他のアプリ使用中も動作させるため、実行中であることを示す通知を表示します。
  [通知を許可する] [今は許可しない]
  ```
- 許可済みの場合はダイアログをスキップ
- 拒否してもアプリはクラッシュせず OverlayService を起動

### 6. バックアップ設定修正

**方針: include のみ指定（明示しないファイルは自動除外）**

採用した方式: 非表示アプリ一覧を専用 SharedPreferences（`pully_hidden_prefs`）に分離し、
一般設定（`ui_config`）・固定アプリ（`pully_prefs`）のみをバックアップ対象にする。
明示的な `<exclude>` は Lint エラーになるため、`<include>` のみで管理する。

| ファイル | バックアップ | 理由 |
|---|---|---|
| `ui_config` (SharedPrefs) | 対象 | ノード数・カラー・透明度・FLAG_SECURE |
| `pully_prefs` (SharedPrefs) | 対象 | 固定アプリ一覧 |
| `pully_hidden_prefs` (SharedPrefs) | **除外** | 端末固有のパッケージ名（プライバシー） |
| `app_icons/` (filesDir) | **除外** | 再生成可能なキャッシュ |

変更したファイル:
- `data/HiddenAppsPrefs.kt` を新規作成（`pully_hidden_prefs` を専用管理）
- `data/UiConfigPrefs.kt` から `KEY_HIDDEN_PKGS` を削除し、HiddenAppsPrefs に委譲
- `backup_rules.xml` を include-only 構成に変更
- `data_extraction_rules.xml` を include-only 構成に変更

### 7. その他の改善

- 通知チャンネル名・通知タイトルを `PullyLuncher` → `PullyLauncher` に修正
- English strings に `setting_ball_alpha` / `hint_ball_alpha` の未翻訳を追加（既存 Lint エラー修正）
- `docs/PLAY_STORE_CHECKLIST.md` を新規作成

---

## ビルド結果

```
.\gradlew.bat clean assembleDebug --no-daemon
BUILD SUCCESSFUL in 10s
33 actionable tasks: 33 executed

.\gradlew.bat lintDebug --no-daemon
BUILD SUCCESSFUL in 18s
Lint errors: 0
```

デバッグ APK 出力先:
```
app\build\outputs\apk\debug\app-debug.apk
```

### Merged Manifest 確認結果

| 確認項目 | 結果 |
|---|---|
| AccessibilityService が残っていないこと | OK（含まれていない） |
| specialUse `<property>` が含まれていること | OK（含まれている） |
| POST_NOTIFICATIONS が含まれていること | OK（含まれている） |
| `<service>` は OverlayService のみ | OK |

---

## 変更・削除したファイル

### 削除
- `ForegroundAppService.kt`
- `res/xml/accessibility_service_config.xml`

### 新規作成
- `data/HiddenAppsPrefs.kt`
- `docs/PLAY_STORE_CHECKLIST.md`

### 変更
- `AndroidManifest.xml`
- `OverlayService.kt`
- `LauncherRepository.kt`
- `MainActivity.kt`
- `data/UiConfigPrefs.kt`
- `ui/theme/SettingsScreen.kt`
- `res/values/strings.xml`
- `res/values-en/strings.xml`
- `res/xml/backup_rules.xml`
- `res/xml/data_extraction_rules.xml`

---

## 変更していないもの

- `FLAG_SECURE` 機能（初期値ON・ユーザー ON/OFF 可・即時反映）
- applicationId = `com.rebirthkreate.pullylauncher`
- compileSdk / targetSdk = 35
- AGP 8.10.1 / Gradle 8.11.1 / Kotlin 2.1.21

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

## Galaxy 実機テスト手順

### インストール

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Logcat フィルタ

```
tag:PullyVisibility
```

### 確認手順

1. アプリ起動 → フローティングランチャーを ON
2. Android 13+ の場合、通知権限ダイアログが表示されることを確認
3. 設定 → フローティング非表示アプリ に「設定」などを追加
4. 権限未付与の場合: 説明テキスト + 「使用履歴へのアクセスを開く」ボタンが表示されることを確認
5. 権限付与後に設定画面に戻ると警告が消えることを確認（ON_RESUME で再チェック）
6. 「設定」アプリを開く → フローティングボールが消えるか確認（最大 850ms 遅延）
7. 別アプリへ切替 → ボールが再表示されるか確認
8. 画面消灯中にポーリングが停止することを確認（Logcat が止まる）
9. Logcat で以下を確認:
   ```
   [UsageStats] fg=com.android.settings
   hidden=true match=true result=HIDE permission=true
   ```

### ポーリング間隔調整

`OverlayService.POLL_INTERVAL_MS = 850L`（ms）

Galaxy での応答感・バッテリー消費を確認後、以下の範囲で調整:
- 応答を速くしたい: 500〜700ms
- バッテリー優先: 1000〜1500ms

---

## 次に実施すべきこと

1. **Galaxy 実機テスト** — 上記手順で動作確認
2. プライバシーポリシーを Web ページとして公開（GitHub Pages 等）
3. keystore 生成・release ビルド設定
4. Play Console — Data Safety・Foreground Service 申告・ストア説明文を入力
5. `docs/PLAY_STORE_CHECKLIST.md` を参照しながら審査準備

---

## ChatGPTに相談したいこと

1. Galaxy 実機で 850ms ポーリングが適切かどうか（応答感とバッテリーのバランス）
2. 非表示アプリ機能で UsageStats 権限がない場合の UX をどう案内するか（現在は警告テキストのみ）
3. プライバシーポリシーの公開方法（GitHub Pages / Google Sites / Notion）
4. Play Console Foreground Service 申告の英語テキストが適切かどうか
5. 審査期間の目安（特別な権限がある場合）
