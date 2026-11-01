# RideOn

プレイヤーを右クリックするとそのプレイヤーに肩車してもらえる(頭の上に乗れる)Paper 用プラグイン(Paper 1.21.11 以降・Java 21 必須)。
リポジトリ: https://github.com/gorogoro-space/RideOn (LGPL v3)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。新しく書くコードのコメントは日本語でよい。既存のコメントは、頼まれない限り書き換えない。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` は Git に入れない(`.git/info/exclude` で除外する)。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。
- プルリクエストをチェックするときは、次の観点で確認して報告する。
  - 変更概要(何を・なぜ変えているか)
  - 脆弱性(権限チェックの漏れ、入力値の検証、SQL の組み立て、パスの扱いなど)
  - 安全面(データの破損・消失、再読み込み時の後始末、他プラグインの妨げなど)
  - 性能(TPS など)の低下(高頻度イベントでの重い処理、メインスレッドでの同期 I/O、定期タスクの追加など。「最重要の設計方針」に沿っているか)

## 環境

- Paper 1.21.11(`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`、`compileOnly`)/ `plugin.yml` の `api-version: '1.21.11'`(1.21.11 未満のサーバーでは読み込まれない)
- **Java 21 必須。** `build.gradle.kts` の toolchain で JDK 21 を使い、Java 21 の class ファイルを出力する。勝手に変えない
- ビルド: Gradle(Kotlin DSL、wrapper 9.7.1)。`gradlew.bat clean build`(Windows)。**JDK 21 で実行する。**「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/RideOn-<version>.jar`
  - IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする
- バージョンは `gradle.properties` の `version` だけで管理する。`plugin.yml` の `version: '${version}'` は `processResources` でビルド時に置き換わる
- SQLite の JDBC ドライバー(`org.sqlite.JDBC`)はサーバー(Paper)に同梱されているものを使う。ビルドの依存には入れていない
- パッケージ: `space.gorogoro.rideon`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- 動作確認はサーバーを再起動して行う。PlugManX での読み込みは権限やコマンドの登録が不完全になることがある

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(PlayerInteractEvent、PlayerMoveEvent など)を使うときは、安い判定を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O をしない(起動時・リロード時に一度だけ行う小さなファイルの読み込みは除く)
  - **今の実装は、右クリック時とコマンド実行時にメインスレッドで SQLite を同期で読み書きしている**(Maven のころからそのまま)。新しく DB を使う処理を足すときは、この形を広げない
- 設定ファイルを追加する場合は、起動時・リロード時に一度だけ解析して保持する
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く

### データの保存
- プラグインフォルダ以外には何も書き込まない
- SQL は `PreparedStatement` のプレースホルダーで値を渡す。文字列の連結で組み立てない

### 他プラグインとの関係
- 既存の機能(例: GSit、看板の click_event など)を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 機能仕様

(現在の実装の動作。仕様を変えたらここを更新する)

- **右クリックで乗る**(`PlayerInteractEntityEvent`): プレイヤーを右クリックすると、その人の乗客の中のプレイヤー(自分以外。複数いれば最後のもの)の上に、いなければその人の上に乗る。次のときは乗らない
  - 右クリックされた人が `/rideon` で肩車を無効にしている(`disablerideon` に UUID がある)
  - 右クリックされた人が `/rideondeny` で右クリックした人を拒否している(`denyrideon` に組み合わせがある)
- **投げ降ろし**(`PlayerInteractEvent`): スニーク中に空気かブロックを左クリックすると、乗客のプレイヤー(複数いれば最後のもの)を降ろし、見ている方向へ速さ 1.0 で飛ばし、`ENTITY_ARROW_SHOOT` の音を鳴らす
- **ログアウト時**(`PlayerQuitEvent`): ログアウトした人がプレイヤーに乗っていれば、その人の乗客を降ろし、本人も降ろす
- **`/rideon`**: 自分への肩車の 無効 / 有効 を切り替える。無効にすると `disablerideon` に追加して「肩車を無効にしました。」、有効に戻すと削除して「肩車を有効にしました。」を出す
- **`/rideondeny <playername>`**: 指定したプレイヤーを自分に乗れないようにする / 解除する。引数が 1 つでなければ「プレイヤー名を指定してください。」。名前は `Bukkit.getOfflinePlayers()` から大文字・小文字を区別して探し、見つからなければ「[名前] not found.」「ターゲット未確認」。追加すると「<名前>をブラックリストに追加しました。」、解除すると「<名前>をブラックリストから削除しました。」
  - UUID と名前を `tp.getPlayer()` から取っているため、相手がオフラインだと例外になり、何も表示されずに終わる(今の実装の動作)
- 共通: 権限ノードは使っていない(plugin.yml に permission の定義はない)。コマンドはプレイヤーのみで、コンソールから実行しても何も起きない
- 例外は `Bukkit.getLogger().info(e.getMessage())` でメッセージだけをログに出す

## データ

- `plugins/RideOn/rideon.db`(SQLite、auto commit 無効で変更ごとに commit)
  - `disablerideon`(`id`、`uuid`、`playername`)— 肩車を無効にしている人
  - `denyrideon`(`id`、`uuid`、`playername`、`owner_uuid`、`owner_playername`)— `owner` が `uuid` の人を拒否
- `plugins/RideOn/config.yml` — 無ければ jar 内の空の config.yml を書き出す。今は読んでいない
- 接続は `onEnable` で開き、`onDisable` で閉じる

## 過去にハマった点(他プロジェクトでの経験)

- `config.getString(path, "")` のように既定値を渡すと、jar 内 config.yml の既定値が参照されない。既定値なしで取得して null を判定すること
- plugin.yml で `default: true` にした権限でも、登録されないと Bukkit は「OP のみ」として扱う。全員向けの機能を権限で縛らない
- 乗客などのエンティティを `remove()` すると内部で降車イベント(`EntityDismountEvent`)が出る。これをキャンセルすると、消えたエンティティが乗ったまま残り、毎 tick 降ろそうとして重くなる

## ファイル構成

- `src/main/java/space/gorogoro/rideon/RideOn.java` — メインクラス。イベント、コマンド処理、DB の読み書きをすべて持つ
- `src/main/resources/plugin.yml` — プラグイン定義、コマンド定義(`rideon`、`rideondeny`)
- `src/main/resources/config.yml` — 空(項目なし)
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` — Gradle の設定(バージョンは `gradle.properties`)
