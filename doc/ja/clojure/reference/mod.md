# mod

`(mod n d)`

床係数です。結果は `d` の符号を引き継ぎ、`rem` は `n` の符号を引き継ぎます。

```clojure
(println (mod -7 2)) ; 1
(println (rem -7 2)) ; -1
```
