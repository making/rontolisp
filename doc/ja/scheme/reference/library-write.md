# (scheme write)

データを現在の出力ポート、または最後の引数に渡したポートに書き出します。

| 名前 | 例 | 結果 |
|---|---|---|
| `display` | `(display '(1 "two" #\3))` | `(1 two 3)` を出力 |
| `write` | `(write '(1 "two" #\3))` | `(1 "two" #\3)` を出力 |
| `write-shared` | `(write-shared (list x x))` | `x` が `(1 2)` なら `(#0=(1 2) #0#)` を出力 |
| `write-simple` | `(write-simple (list x x))` | `x` が `(1 2)` なら `((1 2) (1 2))` を出力 |
