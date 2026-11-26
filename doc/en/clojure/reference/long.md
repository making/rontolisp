# long

`(long x)`

The oracle's `long` cast: a number truncated toward zero, a character's code. A value
outside the long range signals `Value out of range for long: ...`, NaN is `0`, a double
of exactly 2^63 is the largest long, and a non-number signals. As a value a one-argument
function.

```clojure
(println (long -2.7)) ; -2
```
