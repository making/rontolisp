# get-internal-run-time

`(get-internal-run-time)`

Returns the consumed run (CPU) time in milliseconds, suitable for measuring processing time by differencing two readings. The interpreter and JVM return an integer; the WASM backend returns a float. As with `get-internal-real-time`, only the difference between two readings is meaningful, not the absolute value.

```lisp
(get-internal-run-time)
```

Bracket a computation with two calls and subtract to obtain the run time it consumed. The value depends on prior execution, so it is non-deterministic.
