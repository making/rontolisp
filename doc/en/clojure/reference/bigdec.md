# bigdec

`(bigdec x)`

Answers an integer itself, a double as the exact rational of the shortest decimal it prints as (`(bigdec 0.1)` is `1/10`), a ratio only when its decimal expansion is finite (`(bigdec 1/3)` signals, like the oracle), or a decimal string (`"1.5"`, `"1e3"`) parsed. NaN, an infinity, `nil` and a character signal. There is no decimal type, so the result is a plain rational and prints as one (`3/2`, where the oracle prints `1.5M`); `(double (bigdec "1.5"))` is `1.5`. As a value a one-argument function.

```clojure
(println (bigdec 0.1) (double (bigdec "1.5"))) ; 1/10 1.5
```
