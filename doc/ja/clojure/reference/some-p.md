# some?

`(some? x)`

`nil?` の否定です。`false` を含め `nil` 以外のすべてに対して `true` を返します。

```clojure
(println (some? nil)) ; false
(println (some? 0)) ; true
(println (some? false)) ; true
```
