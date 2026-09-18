# (scheme case-lambda)

引数の個数ごとに異なる本体をとる手続きです。

| 名前 | 例 | 結果 |
|---|---|---|
| `case-lambda` | `((case-lambda ((x) (list x)) ((x y) (+ x y))) 1 2)` | `3` |
