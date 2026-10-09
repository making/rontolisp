# 反復

副作用ループは seq ビューを 1 要素ずつ進む（lazy 入力も含む）ネストしたループに低下します。
`for` は strict なコレクション上では strict なリストを、最初の lazy なコレクションから先は
lazy seq を答えます。チャンク化はなく、空の strict な `for` は `nil` です。`dorun`/`doall`
は lazy seq を最後まで realize し、`run!` は各要素に関数を効果のために呼び出します。
`iteration` はステップ関数を繰り返し呼ぶ、seq にも reduce にもできる値を作ります。

| 名前 | 例 | 結果 |
|---|---|---|
| `doseq` | `(println (doseq [x [1 2]] (print x)))` | `12nil` |
| `dotimes` | `(println (dotimes [i 3] (print i)))` | `012nil` |
| `for` | `(println (for [x [1 2 3] :when (odd? x)] (* x 10)))` | `(10 30)` |
| `dorun` | `(println (dorun [1 2 3]))` | `nil` |
| `doall` | `(println (doall [1 2 3]))` | `[1 2 3]` |
| `run!` | `(println (run! inc [1 2]))` | `nil` |
| `iteration` | `(println (vec (iteration (fn [k] (when (< k 3) k)) :initk 0 :kf inc)))` | `[0 1 2]` |
