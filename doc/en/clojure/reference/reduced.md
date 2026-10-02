# reduced

`(reduced x)`

Wraps `x` so a reduction stops and answers it: `reduce`, `reduce-kv`, `transduce`,
`into` and the stepping consumers unwrap it, and `@` reads it back. As a value a
one-argument function.

Deviation: a reduced value prints as its wrapper list, where the oracle prints an
object.

```clojure
(println (reduce (fn [a x] (if (> x 2) (reduced a) (+ a x))) 0 [1 2 3 4])) ; 3
(println @(reduced 5)) ; 5
```
