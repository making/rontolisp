# ensure

`(ensure r)`

ref の値を返します。オラクル同様トランザクションが必要で、読み出しはコミットまで
ピン留めされるため、ref への書き手はトランザクションが終わるまで待ちます（オラクル同様）。

```clojure
(def r (ref 7))
(println (dosync (ensure r))) ; 7
```
