# deref

`(deref ref)`

Reads an atom or volatile, or the value inside a [`reduced`](reduced.md). The reader form `@x` is the same operation. Works as a function
value, so `map`/`reduce` take it bare.

```clojure
(def a (atom 1))
(println (deref a)) ; 1
(println @a) ; 1
(println (map deref [(atom 1) (atom 2)])) ; (1 2)
(println @(reduced 3)) ; 3
```
