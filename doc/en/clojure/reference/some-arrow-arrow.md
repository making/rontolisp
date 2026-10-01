# some->>

`(some->> expr step...)`

Threads `expr` through the steps like `->>`, but stops at the first `nil` value and
answers `nil`; `false` does NOT stop the threading -- only `nil` does, like the
oracle.

```clojure
(println (some->> [1 2] (map inc))) ; (2 3)
(println (some->> nil inc))         ; nil
```
