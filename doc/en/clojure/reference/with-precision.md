# with-precision

`(with-precision precision body...)`

Binds `*math-context*` to the evaluated precision around the body and answers the
last value (`nil` for an empty body); nesting rebinds and leaving restores the root
(`nil`, where the oracle binds its default context). Division stays exact here -- a
ratio, never the oracle's `BigDecimal` -- so the binding scopes the dynamic extent
without rounding anything: `1/3` is the oracle's `0.3333333333M` at precision 10,
`1/2` its `0.5M`. As a value it is unknown (`(def x with-precision)` refuses).

```clojure
(println (with-precision 10 (/ 1 3))) ; 1/3
(println (with-precision 10 *math-context*)) ; 10
```
