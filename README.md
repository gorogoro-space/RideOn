# RideOn
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-brightgreen.svg)](https://fill-ui.papermc.io/projects/paper/version/1.21.11)
[![GitHub release](https://img.shields.io/github/release/gorogoro-space/RideOn.svg)](https://github.com/gorogoro-space/RideOn/releases)
[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/gorogoro-space/RideOn/issues)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://github.com/gorogoro-space/RideOn/blob/master/LICENSE)

RideOn plugin for minecraft.

プレイヤーを右クリックすると、そのプレイヤーに肩車してもらえる(頭の上に乗れる)プラグインです。

# Requirements
- Paper 1.21.11
- Java 21

# Installation method
Please place the .jar file in the Paper plugins folder.

.jar ファイルを Paper の plugins フォルダに置き、サーバーを再起動してください。

# Features
- プレイヤーを右クリックすると、そのプレイヤーの頭の上に乗ります。すでに誰かが乗っている場合は、その上に乗ります
- 乗せている人がスニーク(Shift)しながら左クリックすると、乗っている人を見ている方向へ投げ降ろします(矢を放つ音が鳴ります)
- 乗っている人がログアウトすると、降ろします
- `/rideon` で肩車を無効にしている人や、`/rideondeny` で自分を拒否している人には乗れません
- `/rideonself` で、自分が他の人に乗らないようにできます(うっかり右クリックして乗ってしまうのを防げます)

# Usage
```
/rideon                - Turn on / off shoulder function.(肩車機能をON/OFFします。)
/rideonself            - Turn on / off riding on others.(自分が他の人に乗る機能をON/OFFします。)
/rideondeny <playername> - add / delete deny player.(指定したプレイヤーを肩車拒否に追加/削除します。)
```
- どのコマンドも権限不要で、全員が使えます(プレイヤーのみ。コンソールからは使えません)
- `/rideon` は、実行するたびに自分への肩車の 無効 / 有効 を切り替えます
- `/rideonself` は、実行するたびに「自分が他の人の頭に乗る」の OFF / ON を切り替えます。OFF の間は、他のプレイヤーを右クリックしても乗りません(他の人が自分に乗れるかどうかは `/rideon` で決まります)。設定はログアウトやサーバーの再起動をしても残ります
- `/rideondeny <playername>` は、指定したプレイヤーが自分に乗れないようにします。もう一度実行すると解除します

# Data
`plugins/RideOn/rideon.db`(SQLite)に以下を保存します。
- `disablerideon` — `/rideon` で肩車を無効にしているプレイヤー
- `denyrideon` — `/rideondeny` で拒否したプレイヤーと、拒否した人の組み合わせ

`plugins/RideOn/config.yml` に以下を保存します。
- `self-off-players` — `/rideonself` で、自分が他の人に乗らないようにしているプレイヤーの UUID

書き込みは専用スレッドで、`/rideonself` を実行したときに行います。

# Disclaimer
Do not assume any responsibility by use. Please use it at your own risk.

## ビルド

JDK 21 が必要です。Gradle はラッパー(`gradlew`)が自動でダウンロードするので、別途インストールする必要はありません。

```
gradlew.bat clean build
```

(macOS / Linux では `./gradlew clean build`)

## IntelliJ IDEA でのビルド手順

本プロジェクトはビルドツールに Gradle を使用しています。
IntelliJ IDEA 上で正しくプラグイン（JARファイル）を生成するには、以下の手順を実行してください。
「ビルド → アーティファクトのビルド」はクラスファイルが入らないことがあるので使わないでください。

### 🛠️ ビルド手順

1. IntelliJ IDEA の画面右端にある **「Gradle」タブ** をクリックして開きます。
2. プロジェクト名（RideOn）を展開し、 **`Tasks`** ツリーを開きます。
3. リスト内にある **`clean`** をダブルクリックして実行します（古いビルドキャッシュを削除します）。
4. 続けてリスト内にある **`build`** をダブルクリックして実行します。

### 📦 生成されたファイルの場所
ビルドが成功すると、プロジェクトのルート直下に `build/libs` フォルダが作成（または更新）され、その中に中身の詰まった正しい JAR ファイルが生成されます。

* **生成先:** `build/libs/RideOn-<version>.jar`(例: `RideOn-2.2.jar`。バージョンは `gradle.properties` の `version`)

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

## 開発(IntelliJ IDEA で Claude Code を使う)

このリポジトリには、AI コーディングツール [Claude Code](https://docs.claude.com/ja/docs/claude-code/overview) 向けの作業ルールを書いた `CLAUDE.md` があります。IntelliJ IDEA で使う手順は次のとおりです。

1. Claude の有料プラン(Pro / Max)か、Anthropic API のアカウントを用意します。
2. Windows では、先に [Git for Windows](https://git-scm.com/downloads/win) をインストールします。
3. Claude Code 本体をインストールします。Windows では PowerShell で次を実行します。
   ```
   irm https://claude.ai/install.ps1 | iex
   ```
4. IntelliJ IDEA の「設定 → プラグイン → Marketplace」で「Claude Code」を検索してインストールし、IDE を再起動します。検索結果には Anthropic 以外が作った似た名前のプラグインも表示されます。プラグイン名の下に書かれた提供元が「Anthropic」になっているものを選んでください。
5. プロジェクトを開いて、ターミナルで `claude` を実行します(`Ctrl+Esc` でも起動できます)。初回はブラウザでログインします。

起動すると `CLAUDE.md` が自動で読み込まれ、このプロジェクトの設計方針に沿って作業します。

## ライセンス

[LICENSE](LICENSE) を参照してください。

## kubotan へのメモ

リポジトリを新規作成したら、**必ず Watch を設定すること**(忘れない!)。
GitHub の自動 Watch 機能は 2025 年 5 月に廃止されたため、設定しないと他の人が立てた issue や PR の通知が届かない。

1. リポジトリのページ右上の **「Watch」** を押す
2. **「Custom」** を選び、**Issues** と **Pull requests** にチェックを入れる
