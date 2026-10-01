# frequencies

`(frequencies coll)`

`coll` の seq ビューを一走査した要素の出現回数を新しいマップで返します。空では空マップです。
値としては1引数のラムダです。

```clojure
(println (frequencies [:a :a])) ; {:a 2}
```
