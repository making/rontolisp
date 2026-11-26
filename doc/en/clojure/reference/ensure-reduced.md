# ensure-reduced

`(ensure-reduced x)`

Answers `x` when it is already [`reduced`](reduced.md), else `(reduced x)` -- what the
`take` transducer answers after its last input. As a value a one-argument function.

```clojure
(println (reduced? (ensure-reduced 1)) (unreduced (ensure-reduced (reduced 2)))) ; true 2
```
