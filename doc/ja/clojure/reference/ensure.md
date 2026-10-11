# ensure

`(ensure r)`

ref の値を返します。オラクル同様トランザクションが必要です（オラクルでは ref を他のトランザクションの書き込みから守りますが、ここではその間に他のトランザクションは走りません）。

```clojure
(def r (ref 7))
(println (dosync (ensure r))) ; 7
```
