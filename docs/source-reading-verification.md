# MCAuth・外部SQLite読み取りの回帰検証

## 対象

- SPSMCInsight 基準コミット: `1ca2676deb027fe623959ef8f29d9d4582b85e77`
- 照合したMCAuth: `f642f830d283ce2d4dabca1d5c0061304c22763b`
- MCAuthの `VerificationStore` が作成する `authenticated_players` テーブル:
  `uuid TEXT PRIMARY KEY`, `name TEXT NOT NULL`, `discord_user_id TEXT NOT NULL UNIQUE`,
  `discord_user_name TEXT NOT NULL`

## 変更

1. MCAuthは `mcauth.db` を優先し、対象UUIDの現在の連携状態をSELECTする。
2. DBが無い旧版では `data.yml` を読む。DBの破損・構造不一致・検出後の消失で
   古いYAMLへ戻らない。旧YAMLの読取・構文・セクション構造エラーを空データ扱いしない。
3. 解除済みでは `linked=false` とDiscord ID・名前の空値を渡し、古い現在値を消す。
   初回連携の過去マイルストーンはそのまま残す。連携時刻は最初の観測時刻。
4. SQLiteの読取専用フラグと `mode=ro` を使い、`immutable` を削除。
   本来のロックとWALを使用し、busy timeoutを1,000msに制限。
   DBパスはURIエスケープして特殊文字による別ファイル参照を避ける。
5. SQLiteの `BUSY` / `LOCKED` エラーだけは通常の次回収集で再確認し、ロック解除後に
   復帰する。破損・構造不一致などを再試行対象に広げない。起動時の確認にも適用する。

## 追加テスト（23件）

`MCAuthSourceTest`（12件）:
- 現行スキーマ、対象プレイヤー限定、DBバイト列不変
- コミット済みWALからの連携取得、解除後のID・名前消去
- DBと旧YAMLが共存するときのDB優先
- DB不在時の旧YAML互換、YAMLバイト列不変
- 旧YAML使用開始後にDBが現れた場合の切替
- 一度検出したDBが消えた場合、旧YAMLに戻らずエラー
- 破損DB・DBパスがディレクトリ・テーブル/必須列不足
- データ元なし（ファイルを作成しない）
- 旧YAMLの構文不正・セクション型不正
- 空の旧YAMLの未連携判定

`SqliteReaderTest`（6件）:
- チェックポイント前のコミット済みWAL行を取得し、未コミット行は取得しない。
  同じ接続でも次の読取時に新規コミットを観測する。
- INSERT / UPDATE / DELETE / DROPを拒否し、元DBのバイト列を保持
- 別接続の排他ロックを尊重し、解除後は読み取れる。timeout値も確認。
- 日本語・空白・`?mode=rw&#%` を含むファイル名を正しく扱う
- 欠落・null・ディレクトリを開かず、欠落DBを作らない
- SQLiteではないファイルを変更せずエラー

すべて一時ディレクトリで生成した架空データを使用する。

`SourceRegistryLockTest`（5件）:
- 起動時確認・収集中の排他ロック失敗から、次回収集で復帰し実際のスナップショットを保存
- 破損DBは従来どおり再試行しない
- 実際のSQLiteエラーコードでBUSY/LOCKEDとその拡張コードだけを分類
- ContractBoard/CoreProtectの起動時確認でも一時ロックの情報を保持

## 実行方法

通常は `mvn -B clean verify` で全テストと配布JAR作成を実行する。
今回の検証環境はJDK 25、Maven 3.9.9、コンパイル対象Java 21。
Mockitoの自己attachが制限される環境では既存Jobsテストが起動できないため、
プロジェクト指定バージョンのMockitoを起動時Java agentとして指定する:

```sh
mvn -B clean verify \
  -DargLine="-javaagent:<Mavenローカルリポジトリ>/org/mockito/mockito-core/5.24.0/mockito-core-5.24.0.jar"
```

Mockito設定・依存バージョン・本体POMはこの修正では変更しない。

## 2026-10-12 の結果

- `clean test package`: 成功（上記Java agent指定、公式Maven依存を使用）
- 追加23件 + 既存6件 = **29件成功、失敗・エラー・スキップ0件**
- SQLiteドライバ同梱の配布JAR作成: 成功
- 修正前コードに最初の18件を実行: 16件が失敗。特にWALのコミット済み2行に対して
  旧版は1行しか返さず、旧ロック回避の挙動も再現した。
- `git diff --check`: 成功

## 範囲と注意

- 認証DBの内容・スキーマを書き換えるSQL、journal mode変更、チェックポイントを
  製品コードで実行しない。WAL/SHMのロック・共有メモリ管理はSQLite自身が行う。
- WALサイドカーファイルを読めず、必要な作成もできない環境はエラーになる。
  `immutable` / `nolock` で回避しない。
- [SQLite公式WAL仕様](https://www.sqlite.org/wal.html#read_only_databases) と
  [URIパラメータ仕様](https://www.sqlite.org/uri.html) を参照。
- 実サーバーでのPaper/MCAuth同時稼働、本番ファイルの権限・負荷は未検証。
  本番DB読取、デプロイ、サーバー起動・EULA同意、コミット、pushは行っていない。
