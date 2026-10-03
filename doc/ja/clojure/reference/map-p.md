# map?

`(map? x)`

`clojure.core/map?`: マップまたはレコードなら `true` を返します。値としては1引数の関数です。

```clojure
(defrecord MpR [a])
(println (map? {:a 1}) (map? (->MpR 1)) (map? [[:a 1]]))  ; true true false
```
