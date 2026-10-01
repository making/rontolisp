# nil?

`(nil? x)`

`nil` そのものに対してだけ `true` を返します。`false` は別のオブジェクトで、`nil?` は両者を区別します。

```clojure
(println (nil? nil)) ; true
(println (nil? false)) ; false
(println (nil? 0)) ; false
```
