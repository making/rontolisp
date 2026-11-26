# bigdec

`(bigdec x)`

Answers a decimal, the oracle's `BigDecimal`: a decimal itself, an integer at scale 0, a double as the shortest decimal it prints as (`(bigdec 0.1)` is `0.1M`, `(bigdec 100.0)` `100.0M`), a ratio as its numerator divided by its denominator (`(bigdec 1/3)` signals, like the oracle, unless `with-precision` rounds it), or a string as `new BigDecimal` reads it (`"1.50"`, `"1e3"`; surrounding whitespace signals). NaN, an infinity, `nil` and a character signal. As a value a one-argument function.

```clojure
(println (bigdec 0.1) (bigdec "1.50") (bigdec 1/8) (bigdec 3)) ; 0.1M 1.50M 0.125M 3M
```
