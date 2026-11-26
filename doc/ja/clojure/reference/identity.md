# identity

`(identity x)`

`x` 自体を返します。値としては1引数ラムダなので、裸の `identity` を map できます。

```clojure
(println (identity 1)) ; 1
(println (map identity [1 2])) ; (1 2)
```
