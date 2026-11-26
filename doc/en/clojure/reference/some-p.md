# some?

`(some? x)`

The negation of `nil?`: `true` for everything but `nil`, `false` included.

```clojure
(println (some? nil)) ; false
(println (some? 0)) ; true
(println (some? false)) ; true
```
