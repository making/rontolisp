# (scheme process-context)

プログラムの終了と、そのコマンドライン、環境変数です。

| 名前 | 例 | 結果 |
|---|---|---|
| `exit` | `(exit 3)` | 未実行の `dynamic-wind` の after を実行し、ステータス 3 で終了 |
| `emergency-exit` | `(emergency-exit 4)` | `dynamic-wind` の after を実行せずステータス 4 で終了 |
| `command-line` | `(command-line)` | プログラム名とその後の引数を並べた文字列のリスト |
| `get-environment-variable` | `(get-environment-variable "RONTOLISP_UNSET")` | 環境変数の値を文字列で返す。未設定なら `#f` |
| `get-environment-variables` | `(get-environment-variables)` | すべての環境変数を、文字列の `(name . value)` ペアのリストで返す |
