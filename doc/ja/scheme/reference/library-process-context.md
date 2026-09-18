# (scheme process-context)

プログラムを終了します。ライブラリの残り（コマンドライン、環境変数）は実装されていません。

| 名前 | 例 | 結果 |
|---|---|---|
| `exit` | `(exit 3)` | 未実行の `dynamic-wind` の after を実行し、ステータス 3 で終了 |
| `emergency-exit` | `(emergency-exit 4)` | `dynamic-wind` の after を実行せずステータス 4 で終了 |
