# rontolisp:unix-time

`(rontolisp:unix-time)`

Returns the wall clock as two values: the seconds since the Unix epoch (1970-01-01 GMT) and the nanoseconds within that second (0-999999999). [`get-universal-time`](get-universal-time.md) stops at the second; this is the same clock below it, so readings within one second can be ordered and a duration read from two of them. The primary value is `(- (get-universal-time) 2208988800)`.

The fraction comes from `java.time.Instant` on the interpreter and the JVM, from WASI `clock_time_get` on WASM Preview 1 (and `--native`), from `wasi:clocks/system-clock` under `--component`, and from the nanoseconds the host wrote through `__ronto_set_time` under `--no-wasi`, where the call signals until the host has written one ([clock and randomness guide](../../guides/clock-and-random.md)). How fine it is depends on the host clock: the JVM's and wasmtime's are at least microseconds.

`local-time:now` reads this clock, so its timestamps carry the same fraction.

```lisp
(multiple-value-bind (seconds nanoseconds) (rontolisp:unix-time)
  (list (integerp seconds) (<= 0 nanoseconds 999999999))) ; => (T T)
```

The values differ on every call, so only their types are shown here.
