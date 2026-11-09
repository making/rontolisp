# iterate

`(iterate f x)`

Answers `x`, `(f x)`, `(f (f x))`, ... as a lazy seq, applying through the same call
path as `map` (real functions and collection values alike). Each element is computed
when it is read, from the one before, so reading an element never calls `f` for the
next. The seq never ends: read it through `take`, `take-while`, `nth` and the like.

```clojure
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
```
