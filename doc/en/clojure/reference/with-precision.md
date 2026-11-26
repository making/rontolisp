# with-precision

`(with-precision precision [:rounding mode] body...)`

Binds `*math-context*` to the math context of the evaluated precision and rounding mode
(`HALF_UP` unless `:rounding` names a `RoundingMode` constant, `FLOOR` or
`java.math.RoundingMode/FLOOR`) around the body and answers the last value (`nil` for an
empty body); nesting rebinds and leaving restores the root (`nil`). Decimal arithmetic
rounds each answer to the precision, like the oracle's, so a quotient that never ends
answers instead of signalling; integers, ratios and doubles are untouched. The context
prints as the oracle's `#object` without the identity hash. As a value it is unknown
(`(def x with-precision)` refuses).

```clojure
(println (with-precision 10 (/ 1M 3)) (with-precision 10 (/ 1 3))) ; 0.3333333333M 1/3
(println (with-precision 3 :rounding FLOOR (/ 2M 3))) ; 0.666M
```
