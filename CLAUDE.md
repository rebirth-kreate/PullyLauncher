# CLAUDE.md — PullyLauncher 作業ルール

## 基本ルール

- 大きな変更前は変更内容を説明し、確認を取ってから実装する
- 小さな修正（バグ修正・リファクタ）は直接編集してよい
- 変更前に対象ファイルをバックアップする（`.backup_YYYYMMDD/` に保存）
- コードへのコメントは WHY が非自明な場合のみ書く
- 絵文字は使わない

## 作業後の必須対応

作業が完了したら毎回 `CHATGPT_HANDOFF.md` を最新状態で上書き更新する。

### CHATGPT_HANDOFF.md のルール
- 毎回古い内容を消して最新状態だけ書く
- ChatGPT にそのまま貼れる形式にする
- 長い生ログは不要。判断に必要な情報だけ書く
- エラー文は省略せず書く
- 以下のセクション構成を守る:
  1. 今回の目的
  2. 完了したこと
  3. 現在の問題
  4. 変更したファイル
  5. 変更内容
  6. 実行結果
  7. ChatGPTに相談したいこと

## プロジェクト構成メモ

- パッケージ: `com.rebirthkreate.pullylauncher`
- 言語: Kotlin + Jetpack Compose
- オーバーレイ: `OverlayService`（2ウィンドウ構成）
- フォアグラウンド検知: `ForegroundAppService`（アクセシビリティサービス）+ ポーリングフォールバック
- 設定永続化: `UiConfigPrefs`（SharedPreferences）
- アプリ一覧: `AppRepository` → `UsageHistoryRepository` → `LauncherRepository`

## Gradle 構成（安定版）

| コンポーネント | バージョン |
|---|---|
| AGP | 8.10.1 |
| Gradle | 8.11.1 |
| Kotlin | 2.1.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

バックアップ: `.backup_20260616/`
