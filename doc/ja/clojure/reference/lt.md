# <

`(< x...)`

引数が左から右へ狭義に増えるとき `true`。引数 1 つなら `true` です。真偽値を返し、決して `nil` を返しません。

```clojure
(println (< 1 2)) ; true
(println (< 1 2 2)) ; false
```
