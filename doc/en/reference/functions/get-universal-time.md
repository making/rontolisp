# get-universal-time

`(get-universal-time)`

Returns the current time as the number of seconds since the Common Lisp epoch of 1900-01-01 GMT (Unix time plus 2208988800). Every backend returns an integer; the WASM backend reads the clock from the host (the real host clock in Preview 1, `wasi:clocks@0.3.0` in `--component` mode). A `--no-wasi` module imports no clock and reports the time its host handed over — see the [clock and randomness guide](../../guides/clock-and-random.md#setting-the-clock----ronto-set-time).

```lisp
(get-universal-time)
```

Each call yields the wall-clock time at the moment of the call, so the result is non-deterministic; subtracting two readings gives an elapsed number of seconds.
