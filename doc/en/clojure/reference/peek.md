# peek

`(peek coll)`

Answers a vector's last member or a list's first; `nil` of `nil` or an empty vector.
A string, map, set or lazy seq signals, like the oracle (a strict seq is a list here,
so it peeks at its head). As a value a one-argument function.

```clojure
(println (peek [1 2 3])) ; 3
(println (peek '(1 2 3))) ; 1
```
