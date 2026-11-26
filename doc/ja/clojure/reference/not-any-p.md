# not-any?

`(not-any? pred coll)`

`clojure.core/not-any?`: `coll` のどの要素にも `pred` が偽を返すとき `true` を返します（`some` の否定）。最初に真が返った時点で止まるため、無限 seq でも見つかれば答えます。値としては2引数の関数です。

```clojure
(println (not-any? odd? [2 4]) (not-any? odd? [2 3]))  ; true false
```
