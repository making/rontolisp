# false?

`(false? x)`

false オブジェクトそのものに対してだけ `true` を返します。`nil` は別のオブジェクトで、`false` を返します。

```clojure
(println (false? false)) ; true
(println (false? nil)) ; false
```
