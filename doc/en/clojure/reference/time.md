# time

`(time expr)`

Runs the expression, reports `Elapsed time: N msecs` like the oracle -- `N` a
double millisecond count (whole milliseconds here, `42.0`, where the oracle's
carries nanosecond digits) -- and answers the value. Only the value is
deterministic -- the count never is, so tests pin the shape, never the line.

```clojure
(println (time (+ 40 2))) ; 42
```
