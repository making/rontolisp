# unreduced

`(unreduced x)`

Answers the value inside a [`reduced`](reduced.md) `x`, else `x` itself. As a value a
one-argument function.

```clojure
(println (unreduced (reduced 2)) (unreduced 3)) ; 2 3
```
