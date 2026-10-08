# while

`(while test body...)`

`test` が truthy の間、本体を繰り返し実行し、`nil` を返します。本体は末尾位置ではないので、本体の中の `recur` は拒否されます。

```clojure
(def n (atom 0))
(while (< @n 3) (swap! n inc))
(println @n) ; 3
```
