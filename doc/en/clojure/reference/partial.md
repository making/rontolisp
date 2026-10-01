# partial

`(partial f args...)`

Answers `f` over the fixed arguments plus whatever arrives. The fixed arguments
run once. As a value the function, then the fixed arguments.

```clojure
(println ((partial + 10) 5)) ; 15
(println (map (partial + 10) [1 2])) ; (11 12)
```
