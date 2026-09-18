# (scheme eval)

実行時にデータを評価します。ここでの環境については [eval](../eval.md) を参照してください。

| 名前 | 例 | 結果 |
|---|---|---|
| `eval` | `(eval '(+ 1 2) (interaction-environment))` | `3` |
| `environment` | `(environment '(scheme base))` | `#[environment]` |
