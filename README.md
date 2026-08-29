# SPSMCInsight

PaperMC サーバー向けの、プレイヤー行動を記録して**定着率（リテンション）と離脱地点**を分析するためのデータ収集プラグインです。

集計結果は人が読むための画面ではなく、**AIに読ませる前提のJSON**として週ごとに書き出します。閲覧は運営のみです。

## 動作システム

- Minecraft サーバー: PaperMC
- 対象 API: Paper API `1.20.6-R0.1-SNAPSHOT`
- Java: 21（実行は Java 25 の Paper 26.1.2 を想定）
- ビルドツール: Maven
- 出力形式: Bukkit/Paper プラグイン用の `.jar`
- メインクラス: `dev.spa.insight.InsightPlugin`
- 保存先: `plugins/SPSMCInsight/insight.db`（SQLite）

Paper API は `provided` です。SQLite ドライバ（`sqlite-jdbc`）だけを JAR に同梱しています。同梱される各OS向けのネイティブライブラリのぶん、JARは約13MBになります。

## 何のためのプラグインか

このプラグインが答えようとするのは次の3つです。

1. **いつ入った人が、どれだけ残ったか** — 初参加週ごとのコホートで D1 / D7 / D14 / D30 の残存率を出す
2. **どこで詰まったか** — 初回到達（マイルストーン）を並べたファネルで、人数が落ちる段階を特定する
3. **辞める前に何をしていたか** — セッション単位の滞在時間・ワールド・行動カウンタを丸ごと残す

分析そのもの（JSONを読んで結論を出す作業）はこのプラグインの外側です。

## 記録するもの

### セッション

接続から切断までを1セッションとして、次を記録します。

- 参加時刻、退出時刻、滞在時間、アクティブ時間、放置時間
- 参加ワールド・退出ワールド、ワールド別の滞在ミリ秒
- 初回セッションかどうか、通算何回目か、初参加から何日目か
- 切断理由（`quit` / `kick` / `server_stop_or_crash`）

アクティブ時間は、移動・破壊・設置・インタラクト・インベントリ操作・攻撃・チャットなどの**自発的な操作**があった時間だけを積みます。最後の操作から `active-timeout-seconds`（既定60秒）を超えた分は加算しません。McLevel の判定と同じ考え方です。

### 行動カウンタ

セッションごとに次を数えます（`config.yml` の `tracking` で分類ごとに切れます）。

`blocks_broken` / `blocks_placed` / `ores_mined` / `items_crafted` / `items_picked_up` / `items_dropped` / `items_consumed` / `chat_messages` / `chat_characters` / `commands_used` / `deaths` / `respawns` / `kills_player` / `kills_mob` / `damage_taken` / `damage_dealt` / `inventory_clicks` / `containers_opened` / `interactions` / `advancements` / `world_changes` / `teleports` / `portal_uses` / `beds_entered` / `fish_caught` / `animals_tamed` / `animals_bred` / `villager_trades` / `books_edited` / `distance_cm` / `distance_vehicle_cm` / `distance_flight_cm`

ダメージ量は小数を避けるため100倍の整数で入ります。距離はセンチメートルです。

加えて、内訳（`break` / `place` / `craft` / `command` / `death_cause` / `container` / `mob_kill` / `advancement` など）を種別ごとに残します。1セッションあたりの内訳は上位 `material-breakdown-top` 件（既定40件）だけ残します。

**チャットの文面とコマンドの引数は保存しません。** 件数、文字数、コマンド名だけを残します。

### マイルストーン（初回到達）

`first_join` / `first_interact` / `first_block_break` / `first_block_place` / `first_craft` / `first_chat` / `first_advancement` / `first_ore_mined` / `first_death` / `first_world_change` / `first_resource_world` / `first_build_world` / `first_discord_link` / `reached_level_1` / `first_land_claim` / `first_job_joined` / `first_shop_created` / `first_contract_posted` / `first_contract_accepted` / `second_session` / `returned_next_day` / `playtime_1h` / … など。

到達時刻は**最も古い記録が残ります**。あとから同じ項目を観測しても上書きされません。

## コホートと残存率の定義

- **コホート**: 初参加日が属する ISO 週。週の区切りは **JST の月曜0時**
- **活動日**: `daily_activity` に記録がある日（ログインまたは滞在時間がある日）
- **exact**: 初参加日から**ちょうどN日後**に活動があった人の割合
- **within**: 初参加日の翌日から**N日後までに一度でも**活動があった人の割合
- **mature**: そのコホート全員がN日を経過しているか。`false` なら途中経過の数字

小規模サーバーでは `exact` が0になりやすいため、実務上は `within` を見てください。両方を出しているのは、片方だけだと判断を誤るためです。

## 出力されるファイル

既定では `plugins/SPSMCInsight/exports/<ISO週>/` に、週の明け（JST月曜 0:30、`config.yml` で変更可）に自動で書き出します。

| ファイル | 形式 | 内容 |
| --- | --- | --- |
| `summary.json` | JSON | その週の全体像。参加人数、新規、復帰、カウンタ合計、上位内訳、生成したファイルとサイズ、警告 |
| `cohorts.json` | JSON | 全コホートの D1/D7/D14/D30。メンバー個別の活動日一覧つき |
| `funnel.json` | JSON | マイルストーン到達者数の並びと、コホート別の到達状況 |
| `daily.jsonl` | JSONL | その週の日別活動（1行1プレイヤー1日） |
| `players.jsonl` | JSONL | 全プレイヤーの累計と、外部プラグインから取得した現在値 |
| `milestones.jsonl` | JSONL | 全マイルストーン。初参加からの経過分もつく |
| `sessions.jsonl` | JSONL | その週のセッション明細。カウンタ・ワールド・内訳を入れ子で持つ |
| `events.jsonl` | JSONL | カウンタを1行1指標に展開したもの。再集計しやすい形 |
| `breakdown.jsonl` | JSONL | 内訳を1行1件に展開したもの |
| `sources.json` | JSON | 連携プラグインの状態と、読めなかった理由 |

`summary.json` の `reading_order` に、AIへ渡すときの推奨順を入れています。1ファイルが `file-size-warn-mb`（既定48MB）を超えた場合は `warnings` に記録されるので、分割してから渡してください。

## 連携するプラグイン

導入済みのプラグインから、可能な範囲で現在値を取り込みます（既定5分ごと、接続中のプレイヤーのみ）。

| ソース | 取得元 | 取るもの |
| --- | --- | --- |
| `coreprotect` | `plugins/CoreProtect/database.db` | 過去の活動日（遡及専用。定期取り込みはしない） |
| `essentials` | `plugins/Essentials/userdata/<UUID>.yml` | 所持金、ホーム数、最終ログイン・ログアウト |
| `mclevel` | `plugins/McLevel/data.yml` | レベル、アクティブ秒 |
| `mcauth` | `plugins/MCAuth/data.yml` | Discord 連携の有無とID |
| `contractboard` | `plugins/ContractBoard/irai.db` | 依頼の投稿数・受注数と初回時刻 |
| `vault` | ServicesManager | 残高 |
| `luckperms` | ServicesManager | 主グループ |
| `jobs` | Jobs Reborn API | 就いている職業とレベル |
| `griefprevention` | GriefPrevention API | 保護ブロック数、クレーム数 |
| `quickshop` | QuickShop-Hikari API | 所有ショップ数 |
| `bolt` | Bolt API | コンテナ保護数 |
| `discordsrv` | DiscordSRV API | 連携済み Discord ID |
| `worldguard` | WorldGuard API | 所有リージョン数 |
| `multiverse` | Bukkit / Multiverse-Core | 現在ワールドと環境 |

**他プラグインのデータベースへは書き込みません。**読み取り専用で開きます。

### 読めなかったとき

連携先が未導入、無効、バージョン差でAPIが変わった、といった場合は**その項目だけを欠測**として続行します。サーバーは止まりません。

- `sources.json` に `status`（`available` / `unavailable` / `disabled` / `error`）と理由が残ります
- 取り込みが例外で落ちたソースは、その場で `error` に落として以降の周回では呼びません

外部APIはコンパイル時に依存せず、すべてリフレクションかファイル読み取りで叩いています。相手のプラグインを更新してもこのプラグインのビルドは壊れません。

## 導入前データの遡及

初回起動時に一度だけ、サーバーに残っているファイルから過去を復元します。

| 元データ | 使うもの |
| --- | --- |
| `usercache.json` | UUID と名前の対応 |
| `<world>/stats/<UUID>.json` | 通算プレイ時間、死亡数、採掘数、クラフト数、進捗数 |
| `<world>/advancements/<UUID>.json` | 進捗の達成日時。**初参加日時の推定**と初回到達に使う |
| `<world>/playerdata/<UUID>.dat` | ファイル更新時刻を最終ログインの近似として使う |
| `plugins/Essentials/userdata/` | 最終ログイン・ログアウト時刻 |
| CoreProtect の `co_session` | 実際のログイン・ログアウト履歴（最大180日）。無い場合は `co_block` の操作日で代用 |

復元した日別活動には `source: "backfill"` が付きます。`live` で記録済みの日は上書きしません。

**遡及値は推定です。** とくに次の点に注意してください。

- CoreProtect の保持期間は180日です。それ以前の活動日は復元できません
- 進捗ファイルから推定した初参加日時は、実際の初参加より**遅く**なることがあります（何も進捗を取らずに辞めた人は復元できません）
- `playerdata` の更新時刻は、サーバー側の操作でも変わることがあります
- 復元できるのは「その日に活動があったか」までで、セッション単位の行動カウンタは復元できません

遡及は `insight.db` の `meta` にフラグを残し、二度目以降は実行しません。やり直すときは `/insight backfill --force` を使います。

## 保持期間

生ログは既定で **365日** 保持し、それより古い明細は日次集計（`daily_rollup`）へ畳んでから削除します。畳んだあとも `daily_activity` / `players` / `milestones` は残るため、**コホート分析は過去に遡って成立し続けます**。

## コマンド

権限は `insight.admin`（既定 op）です。

```text
/insight export [週|current]
```

指定した週のJSONを書き出します。引数なしなら先週。週は `2026-W35` の形式です。

```text
/insight backfill [--force]
```

導入前データを遡って復元します。`--force` で再実行します。

```text
/insight status
```

収集件数、接続中セッション数、遡及の実行状況を表示します。

```text
/insight sources
```

連携プラグインごとの状態と理由を表示します。

```text
/insight flush
```

メモリ上のカウンタを今すぐ保存します。

```text
/insight compress
```

保持期間を超えた明細を日次へ畳みます。

## 負荷への配慮

- 移動やインベントリ操作などの高頻度イベントは、ハンドラ内では**メモリ上のカウンタに加算するだけ**です
- SQLite への書き込みは専用スレッド1本にまとめ、既定60秒ごとにフラッシュします
- JSONの書き出しも同じスレッドで行い、メインスレッドを止めません
- 外部プラグインの取り込みは5分間隔で、接続中のプレイヤーのみを対象にします
- リスナーは分類ごとに個別登録します。ある分類の登録が失敗しても、他の記録は続きます

サーバーが落ちた場合、最後のフラッシュまでのデータは残ります。開いたままのセッションは次回起動時に `server_stop_or_crash` として閉じます。

## config.yml

```yaml
flush-interval-seconds: 60      # カウンタをDBへ書き出す間隔
active-timeout-seconds: 60      # アクティブとみなす操作間隔の上限
export:
  enabled: true
  hour: 0                       # 週次エクスポートの実行時刻
  minute: 30
  directory: exports
  file-size-warn-mb: 48         # これを超えたら summary.json に警告を出す
retention:
  enabled: true
  detail-days: 365              # 明細を残す日数
backfill:
  enabled: true
  use-coreprotect: true
  use-vanilla-stats: true
  use-essentials: true
sources: {}                     # ソースIDごとに false で無効化
tracking:
  material-breakdown-top: 40    # 1セッションあたりに残す内訳の件数
```

## ビルド

```sh
mvn -B -DskipTests package
```

`target/spsmc-insight-0.1.0.jar` ができます。

## サーバーへの導入

`spsmc-infra` の `Dockerfile` に、GitHub Release の JAR を SHA-256 検証つきで追加します。McLevel・ContractBoard と同じ経路です。

```dockerfile
ARG INSIGHT_URL=https://github.com/spa77k/spsmc-insight/releases/download/v0.1.0/spsmc-insight-0.1.0.jar
ARG INSIGHT_SHA256=<リリース作成後に算出する>
```

## 分かっていること・分かっていないこと

- **記録するのは事実だけです。** 「なぜ辞めたか」は出力しません。ファネルとセッションの並びから、運営が仮説を立てるための材料を出すところまでです
- **ファネルの並びは想定する体験順であり、実際の到達順ではありません。** `funnel.json` の `drop_from_previous` が負になることがあります（後段の方が到達者が多い場合）。これは並びが実態と違うことを示す情報として、そのまま残しています
- **`first_discord_link` の到達時刻は、連携を最初に観測した時刻です。** MCAuth・DiscordSRV のどちらもリンク日時を保持していないため、実際に連携した時刻ではありません
- **同時接続の多いサーバーでは未検証です。** 移動イベントの加算処理は軽くしていますが、規模が大きい場合は `tracking.movement` を落として様子を見てください
