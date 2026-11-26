# completing

`(completing f)` / `(completing f cf)`

`f` の初期値とステップのアリティに、完了ステップ `cf`（省略時は `identity`）を組み合わせた
畳み込み関数を返します。2引数の `f` でも `transduce` を完了できます。値としては1引数か2引数を
取ります。

```clojure
(println (transduce (comp (take 2) (map inc)) (completing + str) [1 2 3])) ; 5
(println (transduce (map inc) (completing (fn [acc x] (+ acc x))) 0 [1 2])) ; 5
```
