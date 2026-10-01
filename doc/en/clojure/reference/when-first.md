# when-first

`(when-first [x coll] body...)`

Binds the pattern to the head of the seq view and runs the body only when the
collection is non-empty. The collection runs once.

```clojure
(println (when-first [x [1 2]] x)) ; 1
(println (when-first [x []] :body)) ; nil
```
