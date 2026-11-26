# map-entry?

`(map-entry? x)`

`clojure.core/map-entry?`: マップエントリなら `true` を返します。ここではエントリは単なる
2要素のベクターなので、2要素のベクターはすべて `true` になります。オラクルは自分で作った
`[k v]` には `false` を返します（`first`・`seq`・`find` が返すエントリは両方で `true`）。
それ以外の値は `false` です。値としては1引数の関数です。

```clojure
(println (map-entry? (first {:a 1})))  ; true
(println (map-entry? {:a 1}))          ; false
(println (map-entry? [1 2 3]))         ; false
```
