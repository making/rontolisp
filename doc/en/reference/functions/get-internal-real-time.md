# get-internal-real-time

`(get-internal-real-time)`

Returns the elapsed real (wall-clock) time in milliseconds, suitable for timing operations by taking the difference of two readings. Every backend returns an integer. The absolute value is only meaningful relative to another reading, not as a calendar time.

```lisp
(get-internal-real-time)
```

A typical use is `(- (get-internal-real-time) start)` to measure how many milliseconds a computation took. Because it reflects the current clock, the value is non-deterministic.
