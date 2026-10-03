# record?

`(record? x)`

`clojure.core/record?`: レコードなら `true` を返します。通常のマップ、deftype の値、その他の値は `false` です。値としては1引数の関数です。

```clojure
(defrecord RpR [a])
(println (record? (->RpR 1)) (record? {:a 1}))  ; true false
```
