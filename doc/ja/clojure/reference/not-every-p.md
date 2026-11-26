# not-every?

`(not-every? pred coll)`

`clojure.core/not-every?`: `coll` のいずれかの要素に `pred` が偽を返すとき `true` を返します（`every?` の否定）。最初に偽が返った時点で止まります。値としては2引数の関数です。

```clojure
(println (not-every? odd? [1 2]) (not-every? odd? [1 3]))  ; true false
```
