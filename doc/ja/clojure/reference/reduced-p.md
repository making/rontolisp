# reduced?

`(reduced? x)`

`x` が [`reduced`](reduced.md) 値かどうかを `true` か `false` で返します。値としては1引数の
関数です。

```clojure
(println (reduced? (reduced 1)) (reduced? 1)) ; true false
```
