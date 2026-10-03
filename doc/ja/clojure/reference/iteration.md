# 反復

副作用ループは seq ビューを 1 要素ずつ進む（lazy 入力も含む）ネストしたループに低下します。
`for` は strict なコレクション上では strict なリストを、最初の lazy なコレクションから先は
lazy seq を答えます。チャンク化はなく、空の strict な `for` は `nil` です。`dorun`/`doall`
は lazy seq を最後まで realize します。

| 名前 | 例 | 結果 |
|---|---|---|
| `doseq` | `(println (doseq [x [1 2]] (print x)))` | `12nil` |
| `dotimes` | `(println (dotimes [i 3] (print i)))` | `012nil` |
| `for` | `(println (for [x [1 2 3] :when (odd? x)] (* x 10)))` | `(10 30)` |
| `dorun` | `(println (dorun [1 2 3]))` | `nil` |
| `doall` | `(println (doall [1 2 3]))` | `[1 2 3]` |
