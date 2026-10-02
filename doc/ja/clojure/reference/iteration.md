# 反復

副作用ループは seq ビューを 1 要素ずつ進む（lazy 入力も含む）ネストしたループに低下し、
`for` は同じ入れ子で逆順蓄積して strict なリストになります。`for` は lazy seq を答えず、
チャンク化もありません。空の `for` は `nil` です。

| 名前 | 例 | 結果 |
|---|---|---|
| `doseq` | `(println (doseq [x [1 2]] (print x)))` | `12nil` |
| `dotimes` | `(println (dotimes [i 3] (print i)))` | `012nil` |
| `for` | `(println (for [x [1 2 3] :when (odd? x)] (* x 10)))` | `(10 30)` |
| `dorun` | `(println (dorun [1 2 3]))` | `nil` |
| `doall` | `(println (doall [1 2 3]))` | `[1 2 3]` |
