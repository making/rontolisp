# time

`(time expr)`

Runs the expression, reports `Elapsed time: N msecs` like the oracle, and
answers the value. Only the value is deterministic -- the millisecond count
never is, so tests pin the prefix, never the line.

```clojure
(println (time (+ 40 2))) ; 42
```
