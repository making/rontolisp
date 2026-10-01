# ensure

`(ensure r)`

ref 自身を返します。オラクル同様トランザクションが必要です（オラクルでは ref をトランザクションの読み集合に固定しますが、分離対象がないここでは no-op です）。

```clojure
(def r (ref 7))
(println (dosync (ensure r) :ok)) ; :ok
```
