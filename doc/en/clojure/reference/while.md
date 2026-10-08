# while

`(while test body...)`

Runs the body again and again while `test` is truthy, and answers `nil`. A `recur` in
the body is refused: the body is not in tail position.

```clojure
(def n (atom 0))
(while (< @n 3) (swap! n inc))
(println @n) ; 3
```
