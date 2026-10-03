# トランスデューサー

トランスデューサーはオラクルと同じく、畳み込み関数を受け取って畳み込み関数を返す関数です。
`comp` は左から右へ合成し（最初のものが各入力を最初に受け取ります）、プログラム自身の
`(fn [rf] (fn ([] ...) ([acc] ...) ([acc x] ...)))` も組み込みのものと並べて使えます。
`into`・`transduce`・`sequence`・`eduction` がこれを消費し、`reduce`・`reduce-kv`・
`transduce` は `reduced` が返ると止まります。

次の seq 関数は1引数形（`dedupe` と `distinct` は0引数形）がトランスデューサーで、呼び出しでも
値としても使えます: `map`・`filter`・`remove`・`keep`・`keep-indexed`・`map-indexed`・
`take`・`drop`・`take-while`・`drop-while`・`take-nth`・`mapcat`・`partition-all`・
`partition-by`・`interpose`・`dedupe`・`distinct`・`replace`。`cat` はそれ自体がトランスデューサーです。

| Name | Example | Result |
|---|---|---|
| `transduce` | `(transduce (map inc) + [1 2 3])` | `9` |
| `eduction` | `(eduction (filter odd?) (range 6))` | `(1 3 5)` |
| `sequence` | `(sequence (map inc) [1 2])` | `(2 3)` |
| `completing` | `(transduce (take 2) (completing + str) [1 2 3])` | `"3"` |
| `reduced` | `(reduce (fn [a x] (reduced x)) [1 2])` | `2` |
| `reduced?` | `(reduced? (reduced 1))` | `true` |
| `unreduced` | `(unreduced (reduced 1))` | `1` |
| `ensure-reduced` | `(reduced? (ensure-reduced 1))` | `true` |
| `cat` | `(into [] cat [[1] [2 3]])` | `[1 2 3]` |

```clojure
(println (into [] (comp (map inc) (filter odd?)) [1 2 3 4 5])) ; [3 5]
(println (into [] (comp (take 2) (partition-all 3)) (range 10))) ; [[0 1]]
(defn tens [rf] (fn ([] (rf)) ([acc] (rf acc)) ([acc x] (rf acc (* 10 x)))))
(println (into [] tens [1 2])) ; [10 20]
```
