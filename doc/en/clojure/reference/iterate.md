# iterate

`(iterate f x)`

Answers `x`, `(f x)`, `(f (f x))`, ... as a lazy seq, applying through the same call
path as `map` (real functions and collection values alike). Only terminates behind
`take`.

```clojure
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
```
