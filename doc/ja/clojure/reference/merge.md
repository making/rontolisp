# merge

`(merge m ...)`

すべての引数のペアの上に 1 つの新しい表を組み立てます。後のマップが勝ち、どの引数も書き換えられません。`(merge)` は `nil` で、すべて `nil` の引数の merge も `nil` です。最初の引数が record のときだけオラクル同様 record の型が残ります（最初が `nil` なら後のマップが record でもふつうのマップになります）。merge は `conj` をマップ列に畳み込んだものなので、後ろの引数にはソート済みマップ、`[k v]` ベクター、エントリの seq、Java の `Map`（インタプリタと JVM）も渡せます。`nil` は何も加えず、エントリでない要素のリストはエラーになります。

値としてはマップの残り引数列を取るラムダです。

```clojure
(println (count (merge {:a 1} {:b 2})))     ; 2
(println (get (merge {:a 1} {:a 2 :b 3}) :a)) ; 2
(println (get (merge nil {:a 1}) :a))       ; 1
(println (merge))                           ; nil
(println (count ((fn [f] (f {:a 1} {:b 2})) merge))) ; 2
```
