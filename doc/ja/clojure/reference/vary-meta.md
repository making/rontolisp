# vary-meta

`(vary-meta obj f & args)`

`(with-meta obj (apply f (meta obj) args))` です。現在のメタデータに `f` を適用した
メタデータを持つ `obj` のコピーを返します。

```clojure
(def v (vary-meta (with-meta [1] {:a 1}) assoc :b 2))
(println (:b (meta v))) ; 2
```
