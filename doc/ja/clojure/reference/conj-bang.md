# conj!

`(conj!)` `(conj! coll)` `(conj! tr x)`

トランジェント `tr` に `x` をその場で追加し、`tr` を返します。ベクターなら末尾に、セットなら
メンバーとして、マップならエントリーとして（[`conj`](conj.md) が取るもの。ペアでないベクターは
オラクルの `IllegalArgumentException`）加えます。引数なしなら新しいトランジェントのベクター、
1引数ならその引数です。値としても同じ3つのアリティを取ります。

```clojure
(println (persistent! (conj! (transient {}) [:a 1]))) ; {:a 1}
(println (persistent! (reduce conj! (transient []) (range 3)))) ; [0 1 2]
(println (persistent! (conj!))) ; []
```
