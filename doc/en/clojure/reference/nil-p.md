# nil?

`(nil? x)`

`true` for `nil` alone: `false` is a distinct object, and `nil?` tells them apart.

```clojure
(println (nil? nil)) ; true
(println (nil? false)) ; false
(println (nil? 0)) ; false
```
