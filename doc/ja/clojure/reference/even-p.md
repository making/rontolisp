# even?

`(even? n)`

偶数の整数に対して `true`。値としては真偽を返す 1 引数ラムダなので、`filter` に裸のまま渡せます。

```clojure
(println (even? 2)) ; true
(println (filter even? '(1 2 3 4))) ; (2 4)
```
