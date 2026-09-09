# ローカル検証結果 — 2026-09-09

結果: PASS（試験実装の観測・除外ルール）。当初の完全監査の完成条件の達成を意味しません。

環境は既存のローカルサーバーからコピーしたPaper 26.1.2-74、Jobs 5.2.6.6、
EssentialsX 2.22.0、VaultUnlocked 2.20.2、CMILib 1.5.9.9、LuckPerms 5.5.71です。
Dockerコンテナをネットワークなし・ポート公開なしで起動し、テスト後に自動停止しました。
JobsとEssentialsのJARは変更していません。公開サーバーとinfraの追跡ファイルは未変更です。

| ケース | 結果 |
| --- | --- |
| Jobs支払い処理から1.25を入金 | 残高1.25、verified_delta=1.25 |
| 残高イベントの別リスナーで5を0.75へ変更 | 残高0.75、verified_delta=0.75 |
| 10から2.5を引き落とし | 残高7.5、verified_delta=-2.5 |
| 同一tickに2と3を入金 | 残高5、2件ともambiguous。集計対象外 |
| 非同期スレッドで4を入金 | 残高4、unverified_async。集計対象外 |
| JobsPaymentEventをキャンセル | 残高0、cancelled。残高更新の行なし |
| 残高0から100を引き落とし | 残高0、残高更新の行なし。支払いイベントを収入に数えない |
| Jobs経路以外で50を付与 | Jobs監査の行なし |
| PrePayment予定10→2.5に変更してキャンセル | 予定10、変更後2.5、cancelled。再発火した同じイベントは1行 |
| PrePaymentのアクション・対象・ワールド | BREAK / DIAMOND_ORE / worldを保存（イベントフィクスチャ） |
| 観測直前に作成したInsightセッション | session_idがDB上の同じUUIDのセッションに結合 |
| 週次出力 | 監査明細と集計を出力。確認済み純増減の合計-0.5。予定額・キャンセル額を加算しない |
| 上限減額フラグ | 全行NULLを維持。金額差を上限原因と誤認しない |

単体テスト3件も成功しました。照合判定、入れ子取引の除外、SQLite移行・同一ID更新・
未完了行の再起動時処理・小数保存・集計の二重計上防止を確認しています。

2回目の自動検証成果物は`target/jobs-probe-2zr4374c/verification.json`、
`probe.log`、`plugins/SPSMCInsight/insight.db`、`plugins/SPSMCInsight/exports/`にあります。
コンテナ名は`insight-jobs-probe-2zr4374c`（停止済み）です。
最初の探索用コンテナ`insight-jobs-audit-probe`も停止済みで保持しています。

テストではJobsの公開設定`economy-async: false`を使用し、非同期ケースのみ明示的に非同期実行しました。
通常ローカルサーバーの設定とinfraの既定は変更していません。
ネットワークを切った検証のため外部バージョンチェック・認証鍵取得には接続エラーが出ますが、
監査リスナーとDB保存にエラーはありません。

実プレイヤークライアントによる採掘、職業レベル取得、上限到達のE2E検証は未実施です。
`balance_verified`も取引成功応答ではなく、後続残高との照合結果です。
