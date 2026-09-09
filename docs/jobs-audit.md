# Jobsの受動監査（0.1.0）

JobsのJAR・支払い処理・Vaultサービスを変更せず、Insightだけで観測する試験実装です。
「採掘1回の実支払額を完全に監査する」という当初の完成条件には届いていません。

## 取得できるもの

`jobs-audit.enabled: true`（既定）で起動時にリスナーを登録します。Jobs未導入時は他の収集を続行します。
SQLiteのスキーマは2へ移行し、既存テーブルを維持したまま`jobs_audit`を追加します。

| phase | 内容 | 収入集計 |
| --- | --- | --- |
| `pre_payment` | UUID、職業・レベル、アクション、対象、ワールド、LOWEST時点の予定額、イベント変更後の金額・経験値・ポイント、キャンセル | 含めない |
| `payment` | Jobsがプレイヤー単位に合算した支払いイベント。金額、経験値・ポイント、キャンセルのMONITOR時点の観測 | 含めない |
| `balance` | JobsのBufferedPaymentTask → VaultEconomy → Essentialsの残高更新、更新前後と次tickの残高 | `balance_verified`だけを残高増減として集計 |

金額はSQLiteのTEXT列に10進表記で保持します。doubleのJobs APIで既に失われた精度は復元しません。
同じイベントオブジェクトは重複記録せず、初回保存と照合結果は同じIDの行を更新します。
別々の正当なイベントはUUID・時刻・金額が同じでも別行です。
`session_id`は観測時のLiveSessionを捕捉し、既存の単一DB書き込みスレッドでID確定後に保存します。
オフライン支払い等ではNULLです。セッション保持期間を過ぎると結合先が削除されることがあります。
監査明細は保持期間圧縮の対象にせず残します。DB容量には注意してください。

## 残高照合の意味と制約

残高照合の対応対象は **Jobs 5.2.6.6 / Essentials 2.22.0** です。
スタック上のJobs支払い処理を確認し、別リスナーによる入れ子の残高変更は除外します。
通常の`/pay`、`/eco`、ショップ等の取引や、Jobsクエストのコマンド報酬は含めません。

Essentialsの残高イベントは保存前です。同期イベントの終了後、次tickでイベントの最終値と
Essentialsの残高を照合し、その間に別の残高イベントがなかった場合だけ`balance_verified`とします。
これは「後続の残高観測で一致した」という証拠であり、VaultのtransactionSuccess応答でも、
ディスクへの永続化保証でもありません。残高イベントを通らない変更や、他プラグインによる
特殊な経済処理までは保証できません。

- `ambiguous`: 照合前に同一プレイヤーの別の残高更新が発生。金額が一致しても集計しない。
- `unverified_async`: 非同期イベント。処理完了を保証できないので集計しない。
- `balance_mismatch` / `unverified`: 後続残高の不一致・取得不能。
- `unverified_shutdown` / `unverified_restart`: 照合完了前の停止。起動時にpendingを未確認へ変える。
- `observed_monitor`: 非同期JobsPaymentEventのMONITOR時点の金額。入金成功ではない。
- `cancelled`: 同期イベントはディスパッチ終了後、非同期はMONITOR時点で観測したキャンセル。

infraの現行Jobs設定は`economy-async: true`（生成設定の既定値）でした。
その設定のままでは通常のJobs入金は`unverified_async`になり、確認済み残高増減に入りません。
同期支払いを試す場合はJobsの公開設定`economy-async: false`を使用します。
この変更は支払いAPI呼び出しをメインスレッドへ移すので、経済プロバイダーの遅延の影響を受けます。
今回この設定を変更したのは隔離した検証サーバーだけで、infraや公開サーバーには反映していません。

## 取得できないもの

- アクションと支払いバッチ、バッチと残高更新の厳密な対応ID。時刻や同額だけで紐付けない。
- 各アクションの確定支払額、職業別の確定収入・職業従事時間。
- 減額が上限に起因したという証拠。`limit_reduced`はNULL（不明）のまま。
- 入金APIの失敗応答。支払いイベントがあって残高更新がない場合でも、失敗とは推定しない。
- 上限などにより後続イベントが発火しないアクションの最終状態。
- 確定した経験値・ポイント付与。保存するexp/pointsは各イベント段階の値。

予定額と残高差の大小を使って上限減額を推定したり、支払い額を職業へ按分したりしません。

## 週次出力

`/insight export current`で既存出力に次の2ファイルが加わります。

- `jobs-audit.jsonl`: 当該週の全観測。時刻はUTC epoch milliseconds。
- `jobs-summary.json`: phase/status別件数、職業別予定額、プレイヤー別の確認済み残高純増減。

`verified_coins_per_player_hour`の分母は、その週の当該プレイヤーの全記録プレイ時間です。
職業従事時間ではありません。分母がない場合はNULLです。
確認できなかった入金は除外されるため、完全な収入総額ではありません。
支払いイベント・予定額・残高増減を足すと二重計上になるので、合算しないでください。
`income_by_job`はNULLで出力します。連携状態は起動ログと`sources.json`の`last_checked.jobs_audit`で確認できます。

## 検証方法

`mvn test package`で判定、SQLiteの追加移行、同一IDの更新、再起動時の未確認化、
小数保持、週次出力の二重計上防止を確認します。

実サーバーの検証用プラグインは`src/test/server/JobsAuditProbe.java`にあり、本体JARには含まれません。
`python3 src/test/server/run_probe.py /path/to/spsmc-infra/data`で、既存のPaper・プラグインJARを
コピーした新しい`target`配下のサーバーを起動できます。DockerとJDKが必要です。
ネットワークなし・ポート公開なしで起動し、テスト用UUIDだけを使って自動終了します。
テスト用コンテナとデータは削除せず残します。

プローブは実際のJobs `BufferedPaymentTask`、Vault、Essentialsの入出金を実行します。
支払いキャンセルは実際の`BufferedEconomy.payAll`を通します。
PrePayment変更・キャンセル・重複はイベントを直接発火するフィクスチャであり、
プレイヤークライアントによる採掘や上限到達のE2E検証ではありません。
