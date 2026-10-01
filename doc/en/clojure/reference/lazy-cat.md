# lazy-cat

`(lazy-cat expr...)`

Answers the lazy concatenation of the expressions, each behind its own `lazy-seq`:
equivalent to `(concat (lazy-seq expr) ...)` but every member stays lazy, so an
infinite member terminates behind `take`. `(lazy-cat)` is `nil`.

```clojure
(println (take 6 (lazy-cat [0 1] [2 3] (iterate inc 4)))) ; (0 1 2 3 4 5)
```
