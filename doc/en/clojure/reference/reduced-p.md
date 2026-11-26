# reduced?

`(reduced? x)`

Answers whether `x` is a [`reduced`](reduced.md) value, `true` or `false`. As a value a
one-argument function.

```clojure
(println (reduced? (reduced 1)) (reduced? 1)) ; true false
```
