# get-internal-run-time

`(get-internal-run-time)`

Returns the consumed run (CPU) time in milliseconds, suitable for measuring processing time by differencing two readings. Every backend returns an integer. As with `get-internal-real-time`, only the difference between two readings is meaningful, not the absolute value.

```lisp
(get-internal-run-time)
```

Bracket a computation with two calls and subtract to obtain the run time it consumed. The value depends on prior execution, so it is non-deterministic. A `--no-wasi` module has only the one clock its host set, so this reads the same value as `get-internal-real-time` there and a bracket measures zero (see the [clock and randomness guide](../../guides/clock-and-random.md#setting-the-clock----ronto-set-time)).
