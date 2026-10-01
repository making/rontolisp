# coll?

`(coll? x)`

`true` for a list, vector, map or set. `nil`, the booleans, strings (which the
runtime stores as vectors), characters and numbers are no collections, like the
oracle. As a value a one-argument lambda answering `T`-or-`false`.

```clojure
(println (coll? [1]) (coll? nil)) ; true false
```
