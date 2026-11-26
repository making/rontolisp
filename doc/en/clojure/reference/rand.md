# rand

`(rand)` / `(rand bound)`

One draw from the program-owned generator, never a host call per draw: a double
in `[0,1)` bare, one scaled draw with a bound (never a domain check -- a negative
bound answers a negative double, like the oracle's multiply). Only predicates
pin, never values. As a value a rest lambda over the zero- and one-argument
shapes.

```clojure
(println (let [r (rand 5)] (and (<= 0.0 r) (< r 5)))) ; true
```
