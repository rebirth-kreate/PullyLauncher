# Play Store 公開チェックリスト

最終更新: 2026-06-16

---

## Foreground Service 申告

### 機能説明（Play Console 申告文）

**英語（申告フォーム用）:**
```
PullyLauncher uses a foreground service only while the user has enabled the floating launcher.
The service keeps the user-visible launcher overlay available while other apps are being used.
```

**中断された場合の影響:**
```
If the service is interrupted, the floating launcher disappears and cannot respond to
user interaction until the service is restarted.
```

### Foreground Service Type: specialUse

Manifest に `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` を追加済み。
Play Console の FGS 申告でも同内容を記載する。

```
Maintains a user-enabled floating launcher overlay so it remains available
while other apps are in use.
```

### 審査動画で表示する操作手順

1. PullyLauncher を起動
2. フローティングランチャーを ON（必要に応じて通知権限ダイアログで許可）
3. 実行中通知が表示されることを確認
4. 他のアプリへ移動してもフローティングランチャーが表示されることを確認
5. フローティングランチャーから別アプリを起動
6. PullyLauncher の設定へ戻る
7. フローティングランチャーを OFF
8. 通知とオーバーレイが消えることを確認

---

## Data Safety（申告内容）

コードを確認した実際の状態に基づく。

| 項目 | 状態 |
|---|---|
| インターネット通信 | なし |
| 広告 SDK | なし |
| Analytics / Crash reporting | なし |
| Firebase | なし |
| 外部 API | なし |
| 端末識別子の収集 | なし |
| アプリ利用情報 | 端末内のみで処理（外部送信なし） |
| インストール済みアプリ情報 | 端末内のみで処理（外部送信なし） |
| データの外部送信 | なし |
| 第三者との共有 | なし |

### ローカルに保存するデータ

| データ | 保存先 | バックアップ対象 |
|---|---|---|
| UI 設定（ノード数・カラー・透明度・FLAG_SECURE） | SharedPreferences / ui_config | 対象 |
| 固定アプリ一覧（パッケージ名・ラベル） | SharedPreferences / pully_prefs | 対象 |
| 非表示アプリ一覧（パッケージ名） | SharedPreferences / pully_hidden_prefs | **除外**（端末固有情報） |
| アプリアイコンキャッシュ | filesDir/app_icons/ | **除外**（再生成可能） |

### Play Console Data Safety セクションの回答方針

- 「このアプリはユーザーデータを収集または共有しますか？」→ **いいえ**
- 「このアプリは暗号化を使用してデータを転送しますか？」→ 該当なし（送信しない）
- 「ユーザーはデータの削除をリクエストできますか？」→ **はい**（アプリのアンインストールで全データ削除）

---

## 権限の申告

### SYSTEM_ALERT_WINDOW
```
フローティングオーバーレイランチャーを他のアプリの上に常時表示するために使用します。
他のアプリを監視・操作することはありません。
```

### PACKAGE_USAGE_STATS
```
最近使ったアプリ順にランチャーを並び替えるため、および非表示アプリ機能で
現在前面にあるアプリを検知するために使用します。
データは端末内のみで処理し、外部への送信は行いません。
```

### FOREGROUND_SERVICE_SPECIAL_USE
```
フローティングオーバーレイを他のアプリ使用中も維持するために必要です。
ユーザーが明示的に「フローティング開始」を操作したときのみ起動します。
```

---

## プライバシーポリシー（最低限の記載内容）

公開 URL を Play Console の「ストアの掲載情報 → プライバシーポリシー」に設定すること。

```
PullyLauncher プライバシーポリシー

本アプリはインターネット通信を行わず、いかなるデータも外部サーバーへ送信しません。

■ 収集・使用する情報

(1) インストール済みランチャーアプリの一覧
    目的: フローティングランチャーへの表示
    保存: アイコン画像を端末内ストレージに一時保存（アンインストール時に自動削除）

(2) アプリ使用履歴（PACKAGE_USAGE_STATS 権限）
    目的: 最近使ったアプリ順への並び替え、非表示アプリ検知
    保存: なし（端末内で処理のみ）

(3) ユーザー設定
    内容: ボールサイズ・色・固定アプリ（クラウドバックアップ対象）
    内容: 非表示アプリ一覧（クラウドバックアップ対象外）

■ 第三者との共有
なし。

■ お問い合わせ
rebirth.kreative@gmail.com
```

---

## 公開前チェックリスト

- [ ] プライバシーポリシーを Web ページとして公開
- [ ] Play Console に URL を設定
- [ ] keystore 生成（紛失しないよう安全な場所に保管）
- [ ] release ビルド設定（`app/build.gradle.kts` に signingConfigs を追加）
- [ ] `assembleRelease` でリリース APK / AAB 生成
- [ ] Foreground Service 申告を Play Console に入力
- [ ] Data Safety セクションを入力
- [ ] 権限の申告を入力
- [ ] 審査動画を撮影・アップロード
- [ ] ストア説明文（日本語）を入力
- [ ] スクリーンショット（少なくとも 2 枚）をアップロード
- [ ] 内部テスト → クローズドテスト → 本番公開

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

1. 旧アプリをアンインストール → 新 APK をインストール
2. アプリ起動 → フローティングランチャーを ON
3. 権限を設定（使用履歴へのアクセスを許可）
4. 設定 → フローティング非表示アプリ に「設定」を追加
5. 「設定」アプリを開く → フローティングボールが消えるか確認
6. 別アプリへ戻る → ボールが再表示されるか確認
7. Logcat で以下を確認:

```
[UsageStats] fg=com.android.settings
hidden=true match=true result=HIDE permission=true
```

### ポーリング間隔

`OverlayService.POLL_INTERVAL_MS = 850L`（ms）

Galaxy での応答感・バッテリー消費を確認後、必要であれば定数を変更する。
