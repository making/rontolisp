# 反復

副作用ループは seq ビュー上のネストした `dolist` ループに低下し、`for` は同じ入れ子で
逆順蓄積して strict なリストになります。遅延もチャンク化もメモ化もありません。空の
`for` は `nil` です。

| 名前 | 例 | 結果 |
|---|---|---|
| `doseq` | `(println (doseq [x [1 2]] (print x)))` | `12nil` |
| `dotimes` | `(println (dotimes [i 3] (print i)))` | `012nil` |
| `for` | `(println (for [x [1 2 3] :when (odd? x)] (* x 10)))` | `(10 30)` |
| `dorun` | `(println (dorun [1 2 3]))` | `nil` |
| `doall` | `(println (doall [1 2 3]))` | `[1 2 3]` |
